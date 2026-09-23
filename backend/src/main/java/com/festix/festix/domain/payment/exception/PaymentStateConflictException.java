package com.festix.festix.domain.payment.exception;

import com.festix.festix.common.exception.BusinessException;
import com.festix.festix.common.exception.ErrorCode;
import com.festix.festix.domain.payment.entity.PaymentStatus;

public class PaymentStateConflictException extends BusinessException {

    public PaymentStateConflictException(Long paymentId, PaymentStatus expectedStatus, PaymentStatus actualStatus) {
        super(ErrorCode.PAYMENT_STATE_CONFLICT, "Payment is not " + expectedStatus + ": id=" + paymentId
                + ", actualStatus=" + actualStatus);
    }
}
