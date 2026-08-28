package com.festix.festix.domain.payment;

public class PaymentAlreadyExistsException extends RuntimeException {

    public PaymentAlreadyExistsException(Long reservationId, PaymentStatus existingStatus) {
        super("Reservation already has a payment in progress or completed: reservationId=" + reservationId
                + ", existingStatus=" + existingStatus);
    }
}
