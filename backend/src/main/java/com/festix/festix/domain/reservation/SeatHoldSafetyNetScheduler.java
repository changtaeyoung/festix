package com.festix.festix.domain.reservation;

import com.festix.festix.domain.seat.SeatReleaseService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * SQL-truth safety net for seats the Redis expiry path may have missed
 * (dropped keyspace notification, failed TTL key write, etc). Periodically
 * scans for seats still HELD whose owning reservation.end_ttl has already
 * passed, and releases each one through the same conditional-release entry
 * point the Redis listener uses — no separate release logic here. Payment
 * handling is out of scope; there is no payment service yet.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SeatHoldSafetyNetScheduler {

    private final ReservationRepository reservationRepository;
    private final SeatReleaseService seatReleaseService;
    private final MeterRegistry meterRegistry;

    @Scheduled(fixedDelayString = "${festix.reservation.safety-net.interval-millis}")
    public void releaseExpiredHeldSeats() {
        List<Long> seatIds = reservationRepository.findExpiredHeldSeatIds(LocalDateTime.now());
        if (seatIds.isEmpty()) {
            return;
        }

        int recovered = 0;
        for (Long seatId : seatIds) {
            try {
                if (seatReleaseService.releaseHeldSeat(seatId) > 0) {
                    recovered++;
                    meterRegistry.counter("seat_hold.safety_net.recovered").increment();
                }
            } catch (Exception e) {
                log.error("Failed to release seat in safety-net batch: seatId={}", seatId, e);
            }
        }

        log.info("Safety-net batch: found={} recovered={}", seatIds.size(), recovered);
    }
}
