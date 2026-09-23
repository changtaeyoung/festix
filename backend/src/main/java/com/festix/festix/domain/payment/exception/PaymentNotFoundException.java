package com.festix.festix.domain.payment.exception;

import com.festix.festix.common.exception.BusinessException;
import com.festix.festix.common.exception.ErrorCode;

public class PaymentNotFoundException extends BusinessException {

    public PaymentNotFoundException(Long paymentId) {
        super(ErrorCode.PAYMENT_NOT_FOUND, "Payment not found: id=" + paymentId);
    }
}
