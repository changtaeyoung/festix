package com.festix.festix.redis;

import com.festix.festix.domain.seat.SeatReleaseService;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/**
 * Listens for Redis "expired" keyevent notifications. When one of our own
 * seat:hold:{seatId} keys expires, releases the seat by reusing the existing
 * conditional release UPDATE in SeatReleaseService — no separate release
 * logic here.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SeatHoldExpiryListener implements MessageListener {

    private final SeatReleaseService seatReleaseService;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String expiredKey = new String(message.getBody(), StandardCharsets.UTF_8);

        SeatHoldRedisKeys.parseSeatId(expiredKey).ifPresent(seatId -> {
            try {
                seatReleaseService.releaseHeldSeat(seatId);
            } catch (Exception e) {
                log.error("Failed to release seat after Redis hold expiry: seatId={}", seatId, e);
            }
        });
    }
}
