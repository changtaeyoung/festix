package com.festix.festix.redis;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SeatHoldRedisWriter {

    private static final int MAX_ATTEMPTS = 2;

    private final StringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    /**
     * Writes one TTL key per seat, expiring at the same wall-clock instant as
     * the already-persisted reservation.end_ttl. Best-effort: a seat whose
     * write fails after retries is logged/counted and left for the safety-net
     * batch to reconcile — it does not fail the (already-committed) hold.
     */
    public void writeHoldKeys(Long reservationId, List<Long> seatIds, LocalDateTime endTtl) {
        Duration ttl = Duration.between(LocalDateTime.now(), endTtl);
        for (Long seatId : seatIds) {
            writeHoldKey(reservationId, seatId, ttl);
        }
    }

    private void writeHoldKey(Long reservationId, Long seatId, Duration ttl) {
        String key = SeatHoldRedisKeys.key(seatId);
        String value = reservationId.toString();

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                redisTemplate.opsForValue().set(key, value, ttl);
                return;
            } catch (DataAccessException e) {
                log.warn("Failed to write seat hold TTL key (attempt {}/{}): key={}", attempt, MAX_ATTEMPTS, key, e);
            }
        }

        meterRegistry.counter("seat_hold.redis_write.failures").increment();
        log.error("Exhausted retries writing seat hold TTL key, seat has no Redis expiry: key={}", key);
    }
}
