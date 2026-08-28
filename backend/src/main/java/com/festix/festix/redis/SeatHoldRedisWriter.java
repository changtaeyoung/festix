package com.festix.festix.redis;

import com.festix.festix.domain.reservation.SeatHoldTtlExtensionException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
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

    /**
     * Fully resets the TTL of every seat key in a reservation to expire at
     * {@code newEndTtl} — the one-time extension on payment start. Unlike
     * {@link #writeHoldKeys} this is all-or-nothing: the bundle must not end
     * up with seats on divergent expiry times, and Redis is the source of
     * truth for expiry, so any problem here aborts the caller before Postgres
     * is touched.
     *
     * <p>Two phases with no capture-and-revert:
     * <ol>
     *   <li>read-only: verify all N {@code seat:hold:{seatId}} keys exist and
     *       still carry this reservationId. Any miss throws immediately, before
     *       a single key has been mutated — nothing to unwind.</li>
     *   <li>only if all N pass: pipeline the {@code PEXPIREAT} writes to the
     *       same absolute instant.</li>
     * </ol>
     */
    public void resetHoldTtl(Long reservationId, List<Long> seatIds, LocalDateTime newEndTtl) {
        List<String> keys = seatIds.stream().map(SeatHoldRedisKeys::key).toList();
        String expectedValue = reservationId.toString();

        // Phase 1 — verify, mutate nothing.
        List<String> currentValues = redisTemplate.opsForValue().multiGet(keys);
        for (int i = 0; i < seatIds.size(); i++) {
            String current = currentValues == null ? null : currentValues.get(i);
            if (current == null) {
                fail(reservationId, seatIds.get(i), "Redis hold key missing");
            }
            if (!expectedValue.equals(current)) {
                fail(reservationId, seatIds.get(i), "Redis hold key owned by reservationId=" + current);
            }
        }

        // Phase 2 — all keys checked out; apply the new expiry atomically-ish
        // via a single pipeline round trip.
        long expireAtMillis = newEndTtl.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        List<Object> results = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            StringRedisConnection conn = (StringRedisConnection) connection;
            for (String key : keys) {
                conn.pExpireAt(key, expireAtMillis);
            }
            return null;
        });

        for (int i = 0; i < results.size(); i++) {
            if (!Boolean.TRUE.equals(results.get(i))) {
                // Key vanished between phase 1 and here (its own TTL fired in
                // the gap). Rare; the caller aborts and no Postgres write has
                // happened, so the remaining keys just keep their fresh expiry
                // harmlessly until the safety-net batch reconciles.
                fail(reservationId, seatIds.get(i), "PEXPIREAT did not apply (key expired mid-extension)");
            }
        }
    }

    private void fail(Long reservationId, Long seatId, String reason) {
        meterRegistry.counter("seat_hold.redis_extend.failures").increment();
        throw new SeatHoldTtlExtensionException(reservationId, seatId, reason);
    }
}
