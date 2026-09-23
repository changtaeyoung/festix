package com.festix.festix.domain.seat.dto;

import com.festix.festix.domain.seat.entity.Seat;
import com.festix.festix.domain.seat.entity.SeatStatus;
import java.math.BigDecimal;

public record SeatSummaryResponse(
        Long seatId,
        BigDecimal price,
        SeatStatus status) {

    public static SeatSummaryResponse from(Seat seat) {
        return new SeatSummaryResponse(seat.getId(), seat.getPrice(), seat.getStatus());
    }
}
