package com.festix.festix.redis;

import com.festix.festix.domain.payment.entity.CancelReason;
import com.festix.festix.domain.reservation.service.SeatHoldExpiryCoordinator;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/**
 * Listens for Redis "expired" keyevent notifications. When one of our own
 * seat:hold:{seatId} keys expires, releases the seat and cancels any
 * still-PENDING payment on its reservation via SeatHoldExpiryCoordinator —
 * no separate release or cancellation logic here.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SeatHoldExpiryListener implements MessageListener {

    private final SeatHoldExpiryCoordinator seatHoldExpiryCoordinator;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String expiredKey = new String(message.getBody(), StandardCharsets.UTF_8);

        SeatHoldRedisKeys.parseSeatId(expiredKey).ifPresent(seatId -> {
            try {
                seatHoldExpiryCoordinator.handleExpiredHold(seatId, CancelReason.EXPIRED_BY_EVENT);
            } catch (Exception e) {
                log.error("Failed to release seat after Redis hold expiry: seatId={}", seatId, e);
            }
        });
    }
}
