package com.festix.festix.domain.payment;

public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(Long paymentId) {
        super("Payment not found: id=" + paymentId);
    }
}
