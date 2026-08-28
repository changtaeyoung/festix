package com.festix.festix.domain.payment;

import com.festix.festix.domain.reservation.Reservation;
import com.festix.festix.domain.reservation.ReservationItem;
import com.festix.festix.domain.reservation.ReservationItemRepository;
import com.festix.festix.domain.reservation.ReservationNotFoundException;
import com.festix.festix.domain.reservation.ReservationRepository;
import com.festix.festix.domain.reservation.SeatHoldTtlExtender;
import com.festix.festix.domain.seat.Seat;
import com.festix.festix.domain.seat.SeatRepository;
import com.festix.festix.domain.seat.SeatStatus;
import com.festix.festix.domain.seat.SeatUnavailableException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
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
     * PENDING -> COMPLETED, sets paidAt, and sells every seat in the
     * reservation (HELD -> SOLD) in the same transaction — without this, a
     * paid seat stays HELD and the safety-net batch would later release it
     * back to AVAILABLE once end_ttl passes.
     */
    @Transactional
    public void confirmPayment(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        int updated = paymentRepository.confirmPayment(paymentId, LocalDateTime.now());
        if (updated == 0) {
            PaymentStatus actualStatus = paymentRepository.findById(paymentId)
                    .map(Payment::getStatus)
                    .orElseThrow(() -> new PaymentNotFoundException(paymentId));
            throw new PaymentStateConflictException(paymentId, PaymentStatus.PENDING, actualStatus);
        }

        List<Long> seatIds = reservationItemRepository.findByReservationId(payment.getReservation().getId()).stream()
                .map(item -> item.getSeat().getId())
                .sorted()
                .toList();

        for (Long seatId : seatIds) {
            int sold = seatRepository.sellSeat(seatId);
            if (sold == 0) {
                // Payment was just confirmed PENDING -> COMPLETED above, so
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
