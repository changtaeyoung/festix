package com.festix.festix.domain.seat.controller;

import com.festix.festix.common.response.ApiResponse;
import com.festix.festix.domain.seat.dto.SeatSummaryResponse;
import com.festix.festix.domain.seat.repository.SeatRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class SeatController {

    private final SeatRepository seatRepository;

    @GetMapping("/api/festivals/{festivalId}/seats")
    public ApiResponse<List<SeatSummaryResponse>> getSeats(@PathVariable Long festivalId) {
        List<SeatSummaryResponse> seats = seatRepository.findByFestivalIdOrderByIdAsc(festivalId).stream()
                .map(SeatSummaryResponse::from)
                .toList();
        return ApiResponse.success(seats);
    }
}
