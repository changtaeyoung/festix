package com.festix.festix.domain.payment.service;

import com.festix.festix.domain.payment.entity.CancelReason;
import com.festix.festix.domain.payment.entity.Payment;
import com.festix.festix.domain.payment.entity.PaymentStatus;
import com.festix.festix.domain.payment.exception.PaymentAlreadyExistsException;
import com.festix.festix.domain.payment.exception.PaymentNotFoundException;
import com.festix.festix.domain.payment.exception.PaymentStateConflictException;
import com.festix.festix.domain.payment.repository.PaymentRepository;
import com.festix.festix.domain.reservation.entity.Reservation;
import com.festix.festix.domain.reservation.entity.ReservationItem;
import com.festix.festix.domain.reservation.repository.ReservationItemRepository;
import com.festix.festix.domain.reservation.exception.ReservationNotFoundException;
import com.festix.festix.domain.reservation.repository.ReservationRepository;
import com.festix.festix.domain.reservation.service.SeatHoldTtlExtender;
import com.festix.festix.domain.seat.entity.Seat;
import com.festix.festix.domain.seat.repository.SeatRepository;
import com.festix.festix.domain.seat.entity.SeatStatus;
import com.festix.festix.domain.seat.exception.SeatUnavailableException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationItemRepository reservationItemRepository;
    private final SeatRepository seatRepository;
    private final SeatHoldTtlExtender seatHoldTtlExtender;

    /**
     * Self-reference so the two confirmation phases each run in their own
     * transaction. Calling them as plain {@code this.} methods would bypass
     * the Spring proxy and collapse both into one transaction, erasing the
     * intentional commit gap between them. {@code @Lazy} breaks the
     * self-referential wiring cycle at startup.
     */
    @Lazy
    @Autowired
    private PaymentService self;

    /**
     * Plain lookup for callers (e.g. the REST layer) that need the current
     * state of a payment after a mutation, without re-running any state
     * transition.
     */
    @Transactional(readOnly = true)
    public Payment getPayment(Long paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }

    /**
     * Creates the PENDING payment for a reservation, summing seat prices via
     * reservation_item -> seat once and baking the result into Payment.amount
     * — this is never recomputed later. Rejects a reservation that already
     * has a PENDING or COMPLETED payment so a double "pay" click can't create
     * two payment rows for the same bundle.
     *
     * <p>Before the payment row is created, the hold TTL is reset once via
     * {@link SeatHoldTtlExtender} (Redis first, then Postgres, in its own
     * transaction). If Redis can't be updated the extension throws and this
     * whole method aborts without creating a payment.
     */
    @Transactional
    public Payment startPayment(Long reservationId) {
        paymentRepository.findByReservationId(reservationId).stream()
                .filter(p -> p.getStatus() == PaymentStatus.PENDING || p.getStatus() == PaymentStatus.COMPLETED)
                .findFirst()
                .ifPresent(p -> {
                    throw new PaymentAlreadyExistsException(reservationId, p.getStatus());
                });

        List<ReservationItem> items = reservationItemRepository.findByReservationId(reservationId);
        if (items.isEmpty()) {
            throw new ReservationNotFoundException(reservationId);
        }

        seatHoldTtlExtender.extendOnPaymentStart(reservationId);

        BigDecimal amount = items.stream()
                .map(item -> item.getSeat().getPrice())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Reservation reservationRef = reservationRepository.getReferenceById(reservationId);

        Payment payment = Payment.builder()
                .reservation(reservationRef)
                .amount(amount)
                .build();

        return paymentRepository.save(payment);
    }

    /**
     * Two-phase confirmation with a deliberate commit gap:
     * <ol>
     *   <li>{@link #beginConfirm} flips PENDING -> CONFIRMING and commits
     *       immediately.</li>
     *   <li>{@link #completeConfirmation} then does the rest — CONFIRMING ->
     *       COMPLETED plus selling every seat — in a fresh transaction.</li>
     * </ol>
     * The gap between them is the seam where a future simulated PG-response
     * wait will live; nothing fills it yet. This method is intentionally NOT
     * {@code @Transactional} — wrapping both phases would defeat the point.
     *
     * <p>If phase 2 fails, the payment is left CONFIRMING (phase 1 already
     * committed). {@code beginConfirm} stamps {@code confirmingAt} so the
     * stuck-CONFIRMING safety-net batch can later cancel such an orphan once
     * it passes the staleness threshold.
     */
    public void confirmPayment(Long paymentId) {
        self.beginConfirm(paymentId);
        self.completeConfirmation(paymentId);
    }

    /**
     * Phase 1: PENDING -> CONFIRMING, committed on return. A 0-row result
     * means the payment was not PENDING (already confirming/completed/
     * canceled, or gone).
     */
    @Transactional
    public void beginConfirm(Long paymentId) {
        int updated = paymentRepository.beginConfirm(paymentId, LocalDateTime.now());
        if (updated == 0) {
            PaymentStatus actualStatus = paymentRepository.findById(paymentId)
                    .map(Payment::getStatus)
                    .orElseThrow(() -> new PaymentNotFoundException(paymentId));
            throw new PaymentStateConflictException(paymentId, PaymentStatus.PENDING, actualStatus);
        }
    }

    /**
     * Phase 2: CONFIRMING -> COMPLETED, sets paidAt, and sells every seat in
     * the reservation (HELD -> SOLD) in the same transaction — without this, a
     * paid seat stays HELD and the safety-net batch would later release it
     * back to AVAILABLE once end_ttl passes.
     */
    @Transactional
    public void completeConfirmation(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        // Seam: a future simulated PG-response wait belongs here.

        int updated = paymentRepository.confirmPayment(paymentId, LocalDateTime.now());
        if (updated == 0) {
            PaymentStatus actualStatus = paymentRepository.findById(paymentId)
                    .map(Payment::getStatus)
                    .orElseThrow(() -> new PaymentNotFoundException(paymentId));
            throw new PaymentStateConflictException(paymentId, PaymentStatus.CONFIRMING, actualStatus);
        }

        List<Long> seatIds = reservationItemRepository.findByReservationId(payment.getReservation().getId()).stream()
                .map(item -> item.getSeat().getId())
                .sorted()
                .toList();

        for (Long seatId : seatIds) {
            int sold = seatRepository.sellSeat(seatId);
            if (sold == 0) {
                // Payment was just confirmed CONFIRMING -> COMPLETED above, so
                // every one of its seats should still be HELD; a 0 here means
                // a seat was released out from under a completed payment.
                // Roll back the whole confirmation rather than leave a paid
                // reservation with an unsold seat.
                SeatStatus actualSeatStatus = seatRepository.findById(seatId)
                        .map(Seat::getStatus)
                        .orElse(null);
                throw new SeatUnavailableException(seatId, actualSeatStatus);
            }
        }
    }

    /**
     * Cancels a reservation's still-PENDING payment, if any. A 0 result is
     * expected and normal — most expired holds never reach payment, and this
     * is called without holding any lock.
     */
    @Transactional
    public int cancelPendingPayment(Long reservationId, CancelReason reason) {
        return paymentRepository.cancelPendingByReservationId(reservationId, reason.name());
    }

    /**
     * Cancels a single payment stuck in CONFIRMING (CONFIRMING -> CANCELED),
     * called per id by the stuck-CONFIRMING safety-net batch so each cancel
     * commits in its own transaction. A 0 result is expected and normal — the
     * payment may have just completed on its own between the batch's scan and
     * this call.
     */
    @Transactional
    public int cancelStuckConfirming(Long paymentId, CancelReason reason) {
        return paymentRepository.cancelStuckConfirming(paymentId, reason.name());
    }

    /**
     * COMPLETED -> REFUNDED, sets refundedAt, and releases every seat in the
     * reservation (SOLD -> AVAILABLE) in the same transaction. All-or-nothing
     * at the reservation level: partial refund is out of scope, so any seat
     * that fails to release rolls back the whole refund. No pessimistic lock
     * is needed here — unlike an AVAILABLE seat during hold, nothing else in
     * the system transitions a SOLD seat away from SOLD, so the conditional
     * update on the payment row above is the only exactly-once gate required.
     */
    @Transactional
    public void refundPayment(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        int updated = paymentRepository.refundPayment(paymentId, LocalDateTime.now());
        if (updated == 0) {
            PaymentStatus actualStatus = paymentRepository.findById(paymentId)
                    .map(Payment::getStatus)
                    .orElseThrow(() -> new PaymentNotFoundException(paymentId));
            throw new PaymentStateConflictException(paymentId, PaymentStatus.COMPLETED, actualStatus);
        }

        List<Long> seatIds = reservationItemRepository.findByReservationId(payment.getReservation().getId()).stream()
                .map(item -> item.getSeat().getId())
                .sorted()
                .toList();

        for (Long seatId : seatIds) {
            int released = seatRepository.releaseSeat(seatId, SeatStatus.SOLD);
            if (released == 0) {
                // Payment was just refunded COMPLETED -> REFUNDED above, so
                // every one of its seats should still be SOLD; a 0 here means
                // a seat was changed out from under a completed payment.
                // Roll back the whole refund rather than leave some seats
                // released and others still SOLD.
                SeatStatus actualSeatStatus = seatRepository.findById(seatId)
                        .map(Seat::getStatus)
                        .orElse(null);
                throw new SeatUnavailableException(seatId, actualSeatStatus);
            }
        }
    }
}
