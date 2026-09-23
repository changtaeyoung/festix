package com.festix.festix.domain.reservation.exception;

import com.festix.festix.common.exception.BusinessException;
import com.festix.festix.common.exception.ErrorCode;

public class ReservationNotFoundException extends BusinessException {

    public ReservationNotFoundException(Long reservationId) {
        super(ErrorCode.RESERVATION_NOT_FOUND, "Reservation not found or has no seats: id=" + reservationId);
    }
}
