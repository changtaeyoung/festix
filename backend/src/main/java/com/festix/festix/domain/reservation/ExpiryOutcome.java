package com.festix.festix.domain.reservation;

public record ExpiryOutcome(boolean seatReleased, boolean paymentCanceled) {
}
