package com.festix.festix.domain.seat.repository;

import com.festix.festix.domain.seat.entity.Seat;
import java.util.Optional;

public interface SeatLockRepository {

    /**
     * Acquires a row-level PESSIMISTIC_WRITE lock on the seat, waiting up to
     * lockTimeoutMillis for the lock before giving up (DB-level lock wait,
     * unrelated to the reservation hold TTL).
     */
    Optional<Seat> findByIdForUpdate(Long id, long lockTimeoutMillis);
}
