package com.festix.festix.domain.reservation.exception;

/**
 * Thrown when the one-time payment-start TTL extension cannot be applied to
 * Redis — a seat's {@code seat:hold:{seatId}} key is missing or no longer
 * owned by this reservation. Redis is the source of truth for expiry, so the
 * caller ({@code startPayment}) must abort before writing anything to
 * Postgres rather than extend {@code end_ttl} for a hold that Redis considers
 * gone.
 */
public class SeatHoldTtlExtensionException extends RuntimeException {

    public SeatHoldTtlExtensionException(Long reservationId, Long seatId, String reason) {
        super("Cannot extend seat hold TTL: reservationId=" + reservationId
                + ", seatId=" + seatId + " (" + reason + ")");
    }
}
