package com.festix.festix.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.festix.festix.domain.festival.entity.Festival;
import com.festix.festix.domain.festival.repository.FestivalRepository;
import com.festix.festix.domain.payment.entity.CancelReason;
import com.festix.festix.domain.payment.entity.Payment;
import com.festix.festix.domain.payment.entity.PaymentStatus;
import com.festix.festix.domain.payment.repository.PaymentRepository;
import com.festix.festix.domain.payment.service.PaymentService;
import com.festix.festix.domain.payment.service.StuckConfirmingPaymentSafetyNetScheduler;
import com.festix.festix.domain.reservation.entity.Reservation;
import com.festix.festix.domain.reservation.entity.ReservationItem;
import com.festix.festix.domain.reservation.repository.ReservationItemRepository;
import com.festix.festix.domain.reservation.repository.ReservationRepository;
import com.festix.festix.domain.seat.entity.Seat;
import com.festix.festix.domain.seat.repository.SeatRepository;
import com.festix.festix.domain.seat.entity.SeatStatus;
import com.festix.festix.domain.seat.exception.SeatUnavailableException;
import com.festix.festix.domain.user.entity.User;
import com.festix.festix.domain.user.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves the stuck-CONFIRMING safety net against a real Postgres: after the
 * two-phase confirmation's phase 2 fails and leaves a payment durably
 * CONFIRMING, the batch cancels that orphan once it is older than the
 * configured staleness threshold — and leaves a payment that only just
 * entered CONFIRMING alone. Seats are never touched here; that is the seat
 * safety net's job.
 */
@SpringBootTest
@Testcontainers
class StuckConfirmingPaymentSafetyNetIntegrationTest {

    private static final long STALENESS_THRESHOLD_MILLIS = 120_000L;

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Container
    static GenericContainer redis = new GenericContainer("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("festix.payment.safety-net.staleness-threshold-millis",
                () -> STALENESS_THRESHOLD_MILLIS);
    }

    @Autowired
    private StuckConfirmingPaymentSafetyNetScheduler scheduler;
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
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MeterRegistry meterRegistry;

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
    void cancelsPaymentStuckInConfirmingPastTheStalenessThreshold() {
        stickPaymentInConfirming();
        ageConfirmingAt(LocalDateTime.now().minusMinutes(5));
        double before = recoveredCount();

        scheduler.cancelStuckConfirmingPayments();

        Payment canceled = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(canceled.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        assertThat(canceled.getCancelReason()).isEqualTo(CancelReason.STUCK_IN_CONFIRMING.name());
        assertThat(canceled.getConfirmingAt()).isNotNull();
        assertThat(recoveredCount()).isEqualTo(before + 1.0);

        // This batch touches no seats — they are left exactly as phase 2 left them.
        assertThat(seatRepository.findById(seatIds.get(0)).orElseThrow().getStatus())
                .isEqualTo(SeatStatus.AVAILABLE);
        assertThat(seatRepository.findById(seatIds.get(1)).orElseThrow().getStatus())
                .isEqualTo(SeatStatus.HELD);
    }

    @Test
    void leavesPaymentThatOnlyJustEnteredConfirmingAlone() {
        stickPaymentInConfirming();
        double before = recoveredCount();

        scheduler.cancelStuckConfirmingPayments();

        assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.CONFIRMING);
        assertThat(recoveredCount()).isEqualTo(before);
    }

    /**
     * Drives the real two-phase confirmation to failure: phase 1 commits
     * PENDING -> CONFIRMING, then a seat is yanked so phase 2's HELD -> SOLD
     * update affects 0 rows and aborts, leaving the payment durably CONFIRMING.
     */
    private void stickPaymentInConfirming() {
        new TransactionTemplate(transactionManager)
                .execute(status -> seatRepository.releaseSeat(seatIds.get(0), SeatStatus.HELD));

        assertThatThrownBy(() -> paymentService.confirmPayment(paymentId))
                .isInstanceOf(SeatUnavailableException.class);
        assertThat(paymentRepository.findById(paymentId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.CONFIRMING);
    }

    private void ageConfirmingAt(LocalDateTime when) {
        jdbcTemplate.update("update payment set confirming_at = ? where id = ?", when, paymentId);
    }

    private double recoveredCount() {
        return meterRegistry.counter("payment.safety_net.recovered").count();
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
