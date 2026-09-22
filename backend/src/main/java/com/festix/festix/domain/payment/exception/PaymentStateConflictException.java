package com.festix.festix.domain.payment.exception;

import com.festix.festix.domain.payment.entity.PaymentStatus;

public class PaymentStateConflictException extends RuntimeException {

    public PaymentStateConflictException(Long paymentId, PaymentStatus expectedStatus, PaymentStatus actualStatus) {
        super("Payment is not " + expectedStatus + ": id=" + paymentId
                + ", actualStatus=" + actualStatus);
    }
}
