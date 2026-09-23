package com.festix.festix.domain.reservation.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record SeatHoldRequest(
        @NotNull Long userId,
        @NotEmpty List<@NotNull Long> seatIds) {
}
