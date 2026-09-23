package com.festix.festix.common.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {

    INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "C001", "Invalid input value"),
    ENTITY_NOT_FOUND(HttpStatus.NOT_FOUND, "C002", "Entity not found"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "C003", "Method not allowed"),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "C004", "Internal server error"),

    SEAT_NOT_FOUND(HttpStatus.NOT_FOUND, "S001", "Seat not found"),
    SEAT_FESTIVAL_MISMATCH(HttpStatus.BAD_REQUEST, "S002", "Seat does not belong to the requested festival"),
    SEAT_UNAVAILABLE(HttpStatus.CONFLICT, "S003", "Seat is not available"),

    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "R001", "Reservation not found"),
    SEAT_HOLD_EXPIRED(HttpStatus.CONFLICT, "R002", "Seat hold has expired or is no longer owned by this reservation"),

    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "P001", "Payment not found"),
    PAYMENT_STATE_CONFLICT(HttpStatus.CONFLICT, "P002", "Payment is not in the required state"),
    PAYMENT_ALREADY_EXISTS(HttpStatus.CONFLICT, "P003", "Reservation already has a payment in progress or completed");

    private final HttpStatus status;
    private final String code;
    private final String message;

    ErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }
}
