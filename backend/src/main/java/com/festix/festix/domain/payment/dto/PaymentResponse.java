package com.festix.festix.domain.payment.dto;

import com.festix.festix.domain.payment.entity.Payment;
import com.festix.festix.domain.payment.entity.PaymentStatus;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PaymentResponse(
        Long paymentId,
        Long reservationId,
        BigDecimal amount,
        PaymentStatus status,
        LocalDateTime paidAt,
        LocalDateTime refundedAt) {

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getReservation().getId(),
                payment.getAmount(),
                payment.getStatus(),
                payment.getPaidAt(),
                payment.getRefundedAt());
    }
}
