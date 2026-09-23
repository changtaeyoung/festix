package com.festix.festix.domain.seat.exception;

import com.festix.festix.common.exception.BusinessException;
import com.festix.festix.common.exception.ErrorCode;

public class SeatFestivalMismatchException extends BusinessException {

    public SeatFestivalMismatchException(Long seatId, Long expectedFestivalId) {
        super(ErrorCode.SEAT_FESTIVAL_MISMATCH, "Seat does not belong to the requested festival: seatId=" + seatId
                + ", expectedFestivalId=" + expectedFestivalId);
    }
}
