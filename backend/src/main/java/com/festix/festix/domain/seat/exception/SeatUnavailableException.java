package com.festix.festix.domain.seat.exception;

import com.festix.festix.common.exception.BusinessException;
import com.festix.festix.common.exception.ErrorCode;
import com.festix.festix.domain.seat.entity.SeatStatus;

public class SeatUnavailableException extends BusinessException {

    public SeatUnavailableException(Long seatId, SeatStatus actualStatus) {
        super(ErrorCode.SEAT_UNAVAILABLE, "Seat is not available for hold: id=" + seatId + ", status=" + actualStatus);
    }
}
