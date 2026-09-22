package com.festix.festix.domain.seat.service;

import com.festix.festix.domain.seat.entity.SeatStatus;
import com.festix.festix.domain.seat.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single entry point for releasing a HELD seat via the existing conditional
 * UPDATE. Callers include the Redis expiry listener and the safety-net batch
 * — both reuse this rather than issuing their own release query. Returns the
 * row count from the conditional UPDATE (0 or 1): a 0 means the seat was
 * already released elsewhere (e.g. the other path won the race); that's
 * expected, not an error, but callers that need to know whether they
 * actually caused the release (e.g. for recovery metrics) can check it.
 */
@Service
@RequiredArgsConstructor
public class SeatReleaseService {

    private final SeatRepository seatRepository;

    @Transactional
    public int releaseHeldSeat(Long seatId) {
        return seatRepository.releaseSeat(seatId, SeatStatus.HELD);
    }
}
