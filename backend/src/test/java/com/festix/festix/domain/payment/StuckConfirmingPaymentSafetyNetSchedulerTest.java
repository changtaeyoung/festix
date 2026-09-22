package com.festix.festix.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.festix.festix.domain.payment.config.PaymentSafetyNetProperties;
import com.festix.festix.domain.payment.entity.CancelReason;
import com.festix.festix.domain.payment.repository.PaymentRepository;
import com.festix.festix.domain.payment.service.PaymentService;
import com.festix.festix.domain.payment.service.StuckConfirmingPaymentSafetyNetScheduler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StuckConfirmingPaymentSafetyNetSchedulerTest {

    private static final long STALENESS_THRESHOLD_MILLIS = 120_000L;

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentService paymentService;

    private SimpleMeterRegistry meterRegistry;
    private StuckConfirmingPaymentSafetyNetScheduler scheduler;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        scheduler = new StuckConfirmingPaymentSafetyNetScheduler(
                paymentRepository,
                paymentService,
                new PaymentSafetyNetProperties(60_000L, STALENESS_THRESHOLD_MILLIS),
                meterRegistry);
    }

    @Test
    void scansWithCutoffOneThresholdInThePast() {
        when(paymentRepository.findStuckConfirmingIds(any())).thenReturn(List.of());

        LocalDateTime before = LocalDateTime.now();
        scheduler.cancelStuckConfirmingPayments();
        LocalDateTime after = LocalDateTime.now();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(paymentRepository).findStuckConfirmingIds(cutoff.capture());
        assertThat(cutoff.getValue())
                .isAfterOrEqualTo(before.minusNanos(STALENESS_THRESHOLD_MILLIS * 1_000_000))
                .isBeforeOrEqualTo(after.minusNanos(STALENESS_THRESHOLD_MILLIS * 1_000_000));
    }

    @Test
    void doesNothingWhenNoStuckPayments() {
        when(paymentRepository.findStuckConfirmingIds(any())).thenReturn(List.of());

        scheduler.cancelStuckConfirmingPayments();

        verify(paymentService, never()).cancelStuckConfirming(any(), any());
    }

    @Test
    void cancelsEveryStuckPaymentAndCountsOnlyThoseActuallyFlipped() {
        when(paymentRepository.findStuckConfirmingIds(any())).thenReturn(List.of(1L, 2L, 3L));
        when(paymentService.cancelStuckConfirming(eq(1L), any())).thenReturn(1);
        when(paymentService.cancelStuckConfirming(eq(2L), any())).thenReturn(0); // completed on its own
        when(paymentService.cancelStuckConfirming(eq(3L), any())).thenReturn(1);

        scheduler.cancelStuckConfirmingPayments();

        verify(paymentService).cancelStuckConfirming(1L, CancelReason.STUCK_IN_CONFIRMING);
        verify(paymentService).cancelStuckConfirming(2L, CancelReason.STUCK_IN_CONFIRMING);
        verify(paymentService).cancelStuckConfirming(3L, CancelReason.STUCK_IN_CONFIRMING);
        assertThat(meterRegistry.counter("payment.safety_net.recovered").count()).isEqualTo(2.0);
    }

    @Test
    void oneFailingCancelDoesNotStopTheRest() {
        when(paymentRepository.findStuckConfirmingIds(any())).thenReturn(List.of(1L, 2L));
        when(paymentService.cancelStuckConfirming(eq(1L), any()))
                .thenThrow(new RuntimeException("boom"));
        when(paymentService.cancelStuckConfirming(eq(2L), any())).thenReturn(1);

        scheduler.cancelStuckConfirmingPayments();

        verify(paymentService).cancelStuckConfirming(2L, CancelReason.STUCK_IN_CONFIRMING);
        assertThat(meterRegistry.counter("payment.safety_net.recovered").count()).isEqualTo(1.0);
    }
}
