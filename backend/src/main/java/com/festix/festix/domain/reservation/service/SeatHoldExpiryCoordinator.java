package com.festix.festix.domain.reservation.service;

import com.festix.festix.domain.payment.entity.CancelReason;
import com.festix.festix.domain.payment.service.PaymentService;
import com.festix.festix.domain.reservation.dto.ExpiryOutcome;
import com.festix.festix.domain.reservation.repository.ReservationItemRepository;
import com.festix.festix.domain.seat.service.SeatReleaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single entry point for handling an expired seat hold end-to-end: releases
 * the seat and, if its reservation has a still-PENDING payment, cancels that
 * payment in the same transaction. The Redis expiry listener and the
 * safety-net batch both call this instead of SeatReleaseService directly, so
 * releasing a seat can never leave an orphaned PENDING payment behind.
 * SeatReleaseService and PaymentService each stay single-purpose (seat-only /
 * payment-only); this class owns only the cross-aggregate lookup and the
 * transaction boundary that makes the two writes commit or roll back
 * together (the calls below join this method's transaction under Spring's
 * default REQUIRED propagation).
 */
@Service
@RequiredArgsConstructor
public class SeatHoldExpiryCoordinator {

    private final SeatReleaseService seatReleaseService;
    private final PaymentService paymentService;
    private final ReservationItemRepository reservationItemRepository;

    @Transactional
    public ExpiryOutcome handleExpiredHold(Long seatId, CancelReason reason) {
        boolean seatReleased = seatReleaseService.releaseHeldSeat(seatId) > 0;

        // Attempted unconditionally, even if this call didn't win the seat
        // release: the listener and the safety-net batch can race on the
        // same seat, and the payment cancel is an idempotent conditional
        // UPDATE, so whichever caller gets here still cancels it if needed.
        boolean paymentCanceled = reservationItemRepository.findLatestReservationIdBySeatId(seatId)
                .map(reservationId -> paymentService.cancelPendingPayment(reservationId, reason) > 0)
                .orElse(false);

        return new ExpiryOutcome(seatReleased, paymentCanceled);
    }
}
