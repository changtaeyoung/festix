package com.festix.festix.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.festix.festix.domain.festival.Festival;
import com.festix.festix.domain.festival.FestivalRepository;
import com.festix.festix.domain.reservation.Reservation;
import com.festix.festix.domain.reservation.ReservationItem;
import com.festix.festix.domain.reservation.ReservationItemRepository;
import com.festix.festix.domain.reservation.ReservationRepository;
import com.festix.festix.domain.seat.Seat;
import com.festix.festix.domain.seat.SeatRepository;
import com.festix.festix.domain.seat.SeatStatus;
import com.festix.festix.domain.seat.SeatUnavailableException;
import com.festix.festix.domain.user.User;
import com.festix.festix.domain.user.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves the core guarantee of the two-phase confirmation split against a
 * real Postgres: {@code beginConfirm} commits in its own transaction
 * <em>before</em> {@code completeConfirmation} runs, so a failure in phase 2
 * cannot roll the payment back past CONFIRMING. The Mockito tests can only
 * show the two methods are called in order — not that a real commit lands in
 * the gap.
 *
 * <p>Full {@code @SpringBootTest} (not a slice) so the actual
 * {@code @Lazy}-self proxy wiring on PaymentService is exercised; Redis is
 * containerised only because the context needs it to start.
 */
@SpringBootTest
@Testcontainers
class PaymentConfirmationIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Container
    static GenericContainer redis = new GenericContainer("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private PaymentService paymentService;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private SeatRepository seatRepository;
    @Autowired
    private ReservationRepository reservationRepository;
    @Autowired
    private ReservationItemRepository reservationItemRepository;
    @Autowired
    private FestivalRepository festivalRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long paymentId;
    private List<Long> seatIds;

    @BeforeEach
    void seed() {
        Festival festival = festivalRepository.save(Festival.builder()
                .name("Test Festival")
                .eventDate(LocalDate.now().plusDays(30))
                .location("Test Venue")
                .build());
        Seat seatA = seatRepository.save(heldSeat(festival, 10000));
        Seat seatB = seatRepository.save(heldSeat(festival, 20000));
        User user = userRepository.save(newUser());

        Reservation reservation = Reservation.builder()
                .user(user)
                .endTtl(LocalDateTime.now().plusMinutes(5))
                .build();
        reservation.getItems().add(ReservationItem.builder().reservation(reservation).seat(seatA).build());
        reservation.getItems().add(ReservationItem.builder().reservation(reservation).seat(seatB).build());
        reservation = reservationRepository.save(reservation);

        Payment payment = paymentRepository.save(Payment.builder()
                .reservation(reservation)
                .amount(BigDecimal.valueOf(30000))
                .build());

        paymentId = payment.getId();
        seatIds = List.of(seatA.getId(), seatB.getId());
    }

    @AfterEach
    void cleanUp() {
        paymentRepository.deleteAllInBatch();
        reservationItemRepository.deleteAllInBatch();
        reservationRepository.deleteAllInBatch();
        seatRepository.deleteAllInBatch();
        festivalRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    void beginConfirmCommitsBeforeCompleteConfirmationRuns() {
        paymentService.beginConfirm(paymentId);

        // Fresh reads, outside any transaction: only committed state is visible.
        assertThat(statusOf(paymentId)).isEqualTo(PaymentStatus.CONFIRMING);
        assertThat(seatStatuses()).containsExactly(SeatStatus.HELD, SeatStatus.HELD);

        paymentService.completeConfirmation(paymentId);

        Payment completed = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(completed.getPaidAt()).isNotNull();
        assertThat(seatStatuses()).containsExactly(SeatStatus.SOLD, SeatStatus.SOLD);
    }

    @Test
    void confirmPaymentLeavesPaymentConfirmingWhenPhaseTwoRollsBack() {
        // Yank one seat out from under the confirmation so phase 2's HELD->SOLD
        // conditional update affects 0 rows and phase 2 aborts.
        int released = new TransactionTemplate(transactionManager)
                .execute(status -> seatRepository.releaseSeat(seatIds.get(0), SeatStatus.HELD));
        assertThat(released).isEqualTo(1);

        assertThatThrownBy(() -> paymentService.confirmPayment(paymentId))
                .isInstanceOf(SeatUnavailableException.class);

        // If phase 1 shared a transaction with phase 2, the payment would be
        // back at PENDING. It is durably CONFIRMING → phase 1 committed alone.
        assertThat(statusOf(paymentId)).isEqualTo(PaymentStatus.CONFIRMING);
        // Phase 2 rolled back fully: the seat it reached is not left SOLD.
        assertThat(seatRepository.findById(seatIds.get(1)).orElseThrow().getStatus())
                .isEqualTo(SeatStatus.HELD);
        assertThat(seatRepository.findById(seatIds.get(0)).orElseThrow().getStatus())
                .isEqualTo(SeatStatus.AVAILABLE);
    }

    @Test
    void confirmPaymentCompletesAndSellsEverySeatOnHappyPath() {
        paymentService.confirmPayment(paymentId);

        Payment completed = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(completed.getPaidAt()).isNotNull();
        assertThat(seatStatuses()).containsExactly(SeatStatus.SOLD, SeatStatus.SOLD);
    }

    private PaymentStatus statusOf(Long id) {
        return paymentRepository.findById(id).orElseThrow().getStatus();
    }

    private List<SeatStatus> seatStatuses() {
        return seatIds.stream()
                .map(id -> seatRepository.findById(id).orElseThrow().getStatus())
                .toList();
    }

    private static Seat heldSeat(Festival festival, int price) {
        return Seat.builder()
                .festival(festival)
                .price(BigDecimal.valueOf(price))
                .status(SeatStatus.HELD)
                .build();
    }

    private static User newUser() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return User.builder()
                .customId("user-" + suffix)
                .password("password")
                .name("Test User")
                .phone("010-0000-0000")
                .build();
    }
}
