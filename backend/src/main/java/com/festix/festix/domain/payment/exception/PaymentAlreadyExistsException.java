package com.festix.festix.domain.payment.exception;

import com.festix.festix.domain.payment.entity.PaymentStatus;

public class PaymentAlreadyExistsException extends RuntimeException {

    public PaymentAlreadyExistsException(Long reservationId, PaymentStatus existingStatus) {
        super("Reservation already has a payment in progress or completed: reservationId=" + reservationId
                + ", existingStatus=" + existingStatus);
    }
}
