package com.festix.festix.domain.reservation;

import java.time.LocalDateTime;
import java.util.List;

public record ReservationHoldResult(
        Long reservationId,
        List<Long> seatIds,
        LocalDateTime endTtl) {
}
