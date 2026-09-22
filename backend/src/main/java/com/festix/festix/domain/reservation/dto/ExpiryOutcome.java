package com.festix.festix.domain.reservation.dto;

public record ExpiryOutcome(boolean seatReleased, boolean paymentCanceled) {
}
