package com.festix.festix.domain.payment;

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
     * simulated PG-response wait. No timestamp — paidAt is still stamped only
     * at COMPLETED.
     */
    @Modifying(clearAutomatically = true)
    @Query("update Payment p set p.status = com.festix.festix.domain.payment.PaymentStatus.CONFIRMING "
            + "where p.id = :id and p.status = com.festix.festix.domain.payment.PaymentStatus.PENDING")
    int beginConfirm(@Param("id") Long id);

    /**
     * Phase 2 of confirmation: CONFIRMING -> COMPLETED. Only a payment that
     * has already passed through {@link #beginConfirm} qualifies.
     */
    @Modifying(clearAutomatically = true)
    @Query("update Payment p set p.status = com.festix.festix.domain.payment.PaymentStatus.COMPLETED, "
            + "p.paidAt = :paidAt "
            + "where p.id = :id and p.status = com.festix.festix.domain.payment.PaymentStatus.CONFIRMING")
    int confirmPayment(@Param("id") Long id, @Param("paidAt") LocalDateTime paidAt);

    @Modifying(clearAutomatically = true)
    @Query("update Payment p set p.status = com.festix.festix.domain.payment.PaymentStatus.CANCELED, "
            + "p.cancelReason = :cancelReason "
            + "where p.reservation.id = :reservationId "
            + "and p.status = com.festix.festix.domain.payment.PaymentStatus.PENDING")
    int cancelPendingByReservationId(@Param("reservationId") Long reservationId,
            @Param("cancelReason") String cancelReason);

    @Modifying(clearAutomatically = true)
    @Query("update Payment p set p.status = com.festix.festix.domain.payment.PaymentStatus.REFUNDED, "
            + "p.refundedAt = :refundedAt "
            + "where p.id = :id and p.status = com.festix.festix.domain.payment.PaymentStatus.COMPLETED")
    int refundPayment(@Param("id") Long id, @Param("refundedAt") LocalDateTime refundedAt);
}
