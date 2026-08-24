package com.festix.festix.domain.reservation;

import com.festix.festix.redis.SeatHoldRedisWriter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Entry point for holding seats: runs the Postgres hold (SeatHoldService,
 * its own transaction) to completion first, then writes the Redis TTL keys.
 * Redis is written only after the Postgres commit — writing it earlier and
 * then hitting a later rollback would leave a Redis key for a seat that's
 * actually still AVAILABLE in Postgres.
 */
@Service
@RequiredArgsConstructor
public class SeatHoldFacade {

    private final SeatHoldService seatHoldService;
    private final SeatHoldRedisWriter seatHoldRedisWriter;

    public ReservationHoldResult hold(Long userId, Long festivalId, List<Long> seatIds) {
        ReservationHoldResult result = seatHoldService.holdSeats(userId, festivalId, seatIds);
        seatHoldRedisWriter.writeHoldKeys(result.reservationId(), result.seatIds(), result.endTtl());
        return result;
    }
}
