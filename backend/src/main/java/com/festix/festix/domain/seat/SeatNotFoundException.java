package com.festix.festix.domain.seat;

public class SeatNotFoundException extends RuntimeException {

    public SeatNotFoundException(Long seatId) {
        super("Seat not found: id=" + seatId);
    }
}
