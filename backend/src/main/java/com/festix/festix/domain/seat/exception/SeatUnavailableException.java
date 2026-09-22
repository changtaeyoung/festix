package com.festix.festix.domain.seat.exception;

import com.festix.festix.domain.seat.entity.SeatStatus;

public class SeatUnavailableException extends RuntimeException {

    public SeatUnavailableException(Long seatId, SeatStatus actualStatus) {
        super("Seat is not available for hold: id=" + seatId + ", status=" + actualStatus);
    }
}
