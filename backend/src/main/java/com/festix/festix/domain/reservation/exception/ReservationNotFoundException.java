package com.festix.festix.domain.reservation.exception;

public class ReservationNotFoundException extends RuntimeException {

    public ReservationNotFoundException(Long reservationId) {
        super("Reservation not found or has no seats: id=" + reservationId);
    }
}
