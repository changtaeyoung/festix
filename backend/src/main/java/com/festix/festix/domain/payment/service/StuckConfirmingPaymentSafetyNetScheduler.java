package com.festix.festix.domain.payment.service;

import com.festix.festix.domain.payment.config.PaymentSafetyNetProperties;
import com.festix.festix.domain.payment.entity.CancelReason;
import com.festix.festix.domain.payment.repository.PaymentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Safety net for payments orphaned in CONFIRMING. Two-phase confirmation
 * commits {@code beginConfirm} (PENDING -> CONFIRMING) before
 * {@code completeConfirmation} runs, so a failure in phase 2 leaves the
 * payment row CONFIRMING forever. Periodically scans for payments that have
 * been CONFIRMING longer than the configured staleness threshold and
 * transitions each to CANCELED via {@link PaymentService#cancelStuckConfirming}
 * — one transaction per row, no separate cancellation logic here.
 *
 * <p>Deliberately touches no seats: {@link com.festix.festix.domain.reservation.service.SeatHoldSafetyNetScheduler}
 * already releases any seat still HELD past its reservation's end_ttl,
 * independent of payment status. This batch only cleans up the payment row
 * that the seat batch leaves behind (its cancel is guarded on
 * {@code status = PENDING} and skips CONFIRMING).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StuckConfirmingPaymentSafetyNetScheduler {

    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;
    private final PaymentSafetyNetProperties properties;
    private final MeterRegistry meterRegistry;

    @Scheduled(fixedDelayString = "${festix.payment.safety-net.interval-millis}")
    public void cancelStuckConfirmingPayments() {
        LocalDateTime cutoff = LocalDateTime.now()
                .minus(Duration.ofMillis(properties.stalenessThresholdMillis()));

        List<Long> paymentIds = paymentRepository.findStuckConfirmingIds(cutoff);
        if (paymentIds.isEmpty()) {
            return;
        }

        int recovered = 0;
        for (Long paymentId : paymentIds) {
            try {
                int canceled = paymentService.cancelStuckConfirming(
                        paymentId, CancelReason.STUCK_IN_CONFIRMING);
                if (canceled > 0) {
                    recovered++;
                    meterRegistry.counter("payment.safety_net.recovered").increment();
                }
            } catch (Exception e) {
                log.error("Failed to cancel stuck CONFIRMING payment in safety-net batch: paymentId={}",
                        paymentId, e);
            }
        }

        log.info("Payment safety-net batch: found={} recovered={}", paymentIds.size(), recovered);
    }
}
