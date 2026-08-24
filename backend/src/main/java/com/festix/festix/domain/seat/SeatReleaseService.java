package com.festix.festix.domain.seat;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single entry point for releasing a HELD seat via the existing conditional
 * UPDATE. Callers include the Redis expiry listener and (later) the
 * safety-net batch — both reuse this rather than issuing their own release
 * query. A no-op (0 rows) means the seat was already released elsewhere;
 * that's expected, not an error.
 */
@Service
@RequiredArgsConstructor
public class SeatReleaseService {

    private final SeatRepository seatRepository;

    @Transactional
    public void releaseHeldSeat(Long seatId) {
        seatRepository.releaseSeat(seatId, SeatStatus.HELD);
    }
}
