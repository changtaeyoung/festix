package com.festix.festix.domain.seat.exception;

import com.festix.festix.common.exception.BusinessException;
import com.festix.festix.common.exception.ErrorCode;

public class SeatNotFoundException extends BusinessException {

    public SeatNotFoundException(Long seatId) {
        super(ErrorCode.SEAT_NOT_FOUND, "Seat not found: id=" + seatId);
    }
}
