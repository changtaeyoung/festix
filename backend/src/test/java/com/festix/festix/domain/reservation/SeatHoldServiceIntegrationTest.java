package com.festix.festix.domain.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.festix.festix.domain.festival.Festival;
import com.festix.festix.domain.festival.FestivalRepository;
import com.festix.festix.domain.seat.Seat;
import com.festix.festix.domain.seat.SeatRepository;
import com.festix.festix.domain.seat.SeatStatus;
import com.festix.festix.domain.seat.SeatUnavailableException;
import com.festix.festix.domain.user.User;
import com.festix.festix.domain.user.UserRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Exercises SeatHoldService directly against a real Postgres (Testcontainers)
 * to verify pessimistic-lock concurrency control and all-or-nothing rollback.
 * Uses a @DataJpaTest slice + @Import rather than @SpringBootTest so the
 * Redis-wiring beans (RedisConfig, SeatHoldRedisWriter) never enter the
 * context — this test only covers SeatHoldService, not SeatHoldFacade.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import(SeatHoldService.class)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SeatHoldServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private SeatHoldService seatHoldService;

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

    @AfterEach
    void cleanUp() {
        reservationItemRepository.deleteAllInBatch();
        reservationRepository.deleteAllInBatch();
        seatRepository.deleteAllInBatch();
        festivalRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Test
    void concurrentHoldsOnOverlappingSeatSetsAllowExactlyOneWinnerPerContestedSeat() throws Exception {
        Festival festival = festivalRepository.save(newFestival());
        Seat seatShared = seatRepository.save(newSeat(festival));
        Seat seatA = seatRepository.save(newSeat(festival));
        Seat seatB = seatRepository.save(newSeat(festival));
        Seat seatC = seatRepository.save(newSeat(festival));

        Long userA = userRepository.save(newUser()).getId();
        Long userB = userRepository.save(newUser()).getId();
        Long userC = userRepository.save(newUser()).getId();

        List<List<Long>> attempts = List.of(
                List.of(seatShared.getId(), seatA.getId()),
                List.of(seatShared.getId(), seatB.getId()),
                List.of(seatShared.getId(), seatC.getId()));
        List<Long> userIds = List.of(userA, userB, userC);

        // Forces genuine simultaneous contention: each thread blocks on the
        // barrier immediately before calling holdSeats(), so all 3 race for
        // the pessimistic lock at the same instant instead of potentially
        // running one-at-a-time in submission order.
        CyclicBarrier startBarrier = new CyclicBarrier(attempts.size());

        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            List<Callable<ReservationHoldResult>> tasks = new ArrayList<>();
            for (int i = 0; i < attempts.size(); i++) {
                Long userId = userIds.get(i);
                List<Long> seatIds = attempts.get(i);
                tasks.add(() -> {
                    startBarrier.await();
                    return seatHoldService.holdSeats(userId, festival.getId(), seatIds);
                });
            }

            List<Future<ReservationHoldResult>> futures = executor.invokeAll(tasks);

            int successCount = 0;
            int failureCount = 0;
            for (Future<ReservationHoldResult> future : futures) {
                try {
                    future.get(10, TimeUnit.SECONDS);
                    successCount++;
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(SeatUnavailableException.class);
                    failureCount++;
                }
            }

            assertThat(successCount).isEqualTo(1);
            assertThat(failureCount).isEqualTo(2);
        } finally {
            executor.shutdown();
        }

        Seat sharedAfter = seatRepository.findById(seatShared.getId()).orElseThrow();
        assertThat(sharedAfter.getStatus()).isEqualTo(SeatStatus.HELD);

        long heldCount = List.of(seatA, seatB, seatC).stream()
                .map(seat -> seatRepository.findById(seat.getId()).orElseThrow())
                .filter(seat -> seat.getStatus() == SeatStatus.HELD)
                .count();
        assertThat(heldCount).isEqualTo(1);

        long availableCount = List.of(seatA, seatB, seatC).stream()
                .map(seat -> seatRepository.findById(seat.getId()).orElseThrow())
                .filter(seat -> seat.getStatus() == SeatStatus.AVAILABLE)
                .count();
        assertThat(availableCount).isEqualTo(2);

        assertThat(reservationRepository.count()).isEqualTo(1);
        Long onlyReservationId = reservationRepository.findAll().get(0).getId();
        assertThat(reservationItemRepository.findByReservationId(onlyReservationId)).hasSize(2);
    }

    @Test
    void holdFailsAtomicallyWhenOneSeatIsAlreadySold() {
        Festival festival = festivalRepository.save(newFestival());
        Seat available1 = seatRepository.save(newSeat(festival));
        Seat sold = seatRepository.save(newSeat(festival, SeatStatus.SOLD));
        Seat available2 = seatRepository.save(newSeat(festival));

        Long userId = userRepository.save(newUser()).getId();

        assertThatThrownBy(() -> seatHoldService.holdSeats(
                        userId,
                        festival.getId(),
                        List.of(available1.getId(), sold.getId(), available2.getId())))
                .isInstanceOf(SeatUnavailableException.class);

        Seat available1After = seatRepository.findById(available1.getId()).orElseThrow();
        Seat available2After = seatRepository.findById(available2.getId()).orElseThrow();
        Seat soldAfter = seatRepository.findById(sold.getId()).orElseThrow();

        assertThat(available1After.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(available2After.getStatus()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(soldAfter.getStatus()).isEqualTo(SeatStatus.SOLD);

        assertThat(reservationRepository.count()).isZero();
        assertThat(reservationItemRepository.count()).isZero();
    }

    private static Festival newFestival() {
        return Festival.builder()
                .name("Test Festival")
                .eventDate(LocalDate.now().plusDays(30))
                .location("Test Venue")
                .build();
    }

    private static Seat newSeat(Festival festival) {
        return newSeat(festival, SeatStatus.AVAILABLE);
    }

    private static Seat newSeat(Festival festival, SeatStatus status) {
        return Seat.builder()
                .festival(festival)
                .price(BigDecimal.valueOf(50000))
                .status(status)
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
