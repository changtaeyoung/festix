package com.festix.festix.domain.seat.exception;

public class SeatFestivalMismatchException extends RuntimeException {

    public SeatFestivalMismatchException(Long seatId, Long expectedFestivalId) {
        super("Seat does not belong to the requested festival: seatId=" + seatId
                + ", expectedFestivalId=" + expectedFestivalId);
    }
}
