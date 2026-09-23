package com.festix.festix.domain.payment.exception;

import com.festix.festix.common.exception.BusinessException;
import com.festix.festix.common.exception.ErrorCode;
import com.festix.festix.domain.payment.entity.PaymentStatus;

public class PaymentAlreadyExistsException extends BusinessException {

    public PaymentAlreadyExistsException(Long reservationId, PaymentStatus existingStatus) {
        super(ErrorCode.PAYMENT_ALREADY_EXISTS, "Reservation already has a payment in progress or completed: reservationId=" + reservationId
                + ", existingStatus=" + existingStatus);
    }
}
