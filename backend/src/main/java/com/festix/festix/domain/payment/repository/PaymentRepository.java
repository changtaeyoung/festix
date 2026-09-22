package com.festix.festix.domain.payment.repository;

import com.festix.festix.domain.payment.entity.Payment;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByReservationId(Long reservationId);

    /**
     * Phase 1 of confirmation: PENDING -> CONFIRMING, committed on its own so
     * the gap before {@link #confirmPayment} is a real seam for a future
     * simulated PG-response wait. Stamps {@code confirmingAt} in the same
     * conditional UPDATE so the stuck-CONFIRMING safety net can measure how
     * long a row has been in this state; paidAt is still stamped only at
     * COMPLETED.
     */
    @Modifying(clearAutomatically = true)
    @Query("update Payment p set p.status = com.festix.festix.domain.payment.entity.PaymentStatus.CONFIRMING, "
            + "p.confirmingAt = :confirmingAt "
            + "where p.id = :id and p.status = com.festix.festix.domain.payment.entity.PaymentStatus.PENDING")
    int beginConfirm(@Param("id") Long id, @Param("confirmingAt") LocalDateTime confirmingAt);

    /**
     * Phase 2 of confirmation: CONFIRMING -> COMPLETED. Only a payment that
     * has already passed through {@link #beginConfirm} qualifies.
     */
    @Modifying(clearAutomatically = true)
    @Query("update Payment p set p.status = com.festix.festix.domain.payment.entity.PaymentStatus.COMPLETED, "
            + "p.paidAt = :paidAt "
            + "where p.id = :id and p.status = com.festix.festix.domain.payment.entity.PaymentStatus.CONFIRMING")
    int confirmPayment(@Param("id") Long id, @Param("paidAt") LocalDateTime paidAt);

    @Modifying(clearAutomatically = true)
    @Query("update Payment p set p.status = com.festix.festix.domain.payment.entity.PaymentStatus.CANCELED, "
            + "p.cancelReason = :cancelReason "
            + "where p.reservation.id = :reservationId "
            + "and p.status = com.festix.festix.domain.payment.entity.PaymentStatus.PENDING")
    int cancelPendingByReservationId(@Param("reservationId") Long reservationId,
            @Param("cancelReason") String cancelReason);

    @Modifying(clearAutomatically = true)
    @Query("update Payment p set p.status = com.festix.festix.domain.payment.entity.PaymentStatus.REFUNDED, "
            + "p.refundedAt = :refundedAt "
            + "where p.id = :id and p.status = com.festix.festix.domain.payment.entity.PaymentStatus.COMPLETED")
    int refundPayment(@Param("id") Long id, @Param("refundedAt") LocalDateTime refundedAt);

    /**
     * Payments left CONFIRMING since before {@code cutoff} — phase 1 of
     * confirmation committed but phase 2 never finished. Returns ids only,
     * mirroring the seat safety net's id-list scan. A NULL {@code confirmingAt}
     * (only possible for rows predating the column) never matches and is out
     * of scope.
     */
    @Query("select p.id from Payment p "
            + "where p.status = com.festix.festix.domain.payment.entity.PaymentStatus.CONFIRMING "
            + "and p.confirmingAt < :cutoff")
    List<Long> findStuckConfirmingIds(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Safety-net cancel for a payment stuck in CONFIRMING: CONFIRMING ->
     * CANCELED. The {@code status = CONFIRMING} guard keeps this race-safe
     * against a concurrent {@link #confirmPayment} win — the loser affects 0
     * rows, which the caller treats as benign. {@code confirmingAt} is left
     * as the historical record of when the row entered CONFIRMING.
     */
    @Modifying(clearAutomatically = true)
    @Query("update Payment p set p.status = com.festix.festix.domain.payment.entity.PaymentStatus.CANCELED, "
            + "p.cancelReason = :cancelReason "
            + "where p.id = :id and p.status = com.festix.festix.domain.payment.entity.PaymentStatus.CONFIRMING")
    int cancelStuckConfirming(@Param("id") Long id, @Param("cancelReason") String cancelReason);
}
