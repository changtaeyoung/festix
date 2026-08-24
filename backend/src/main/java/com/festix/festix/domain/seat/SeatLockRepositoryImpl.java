package com.festix.festix.domain.seat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.util.Map;
import java.util.Optional;

public class SeatLockRepositoryImpl implements SeatLockRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Optional<Seat> findByIdForUpdate(Long id, long lockTimeoutMillis) {
        Map<String, Object> lockHints = Map.of("jakarta.persistence.lock.timeout", (int) lockTimeoutMillis);
        Seat seat = entityManager.find(Seat.class, id, LockModeType.PESSIMISTIC_WRITE, lockHints);
        return Optional.ofNullable(seat);
    }
}
