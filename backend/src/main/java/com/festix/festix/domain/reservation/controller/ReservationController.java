package com.festix.festix.domain.reservation.controller;

import com.festix.festix.common.response.ApiResponse;
import com.festix.festix.domain.reservation.dto.ReservationHoldResult;
import com.festix.festix.domain.reservation.dto.SeatHoldRequest;
import com.festix.festix.domain.reservation.service.SeatHoldFacade;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ReservationController {

    private final SeatHoldFacade seatHoldFacade;

    /**
     * {@code userId} is accepted as trusted, unauthenticated client input —
     * there is no auth layer yet (planned as its own step before
     * WebSocket/frontend work). A real deployment would derive it from a
     * session/JWT instead of the request body.
     */
    @PostMapping("/api/festivals/{festivalId}/reservations")
    public ResponseEntity<ApiResponse<ReservationHoldResult>> holdSeats(
            @PathVariable Long festivalId,
            @Valid @RequestBody SeatHoldRequest request) {
        ReservationHoldResult result = seatHoldFacade.hold(request.userId(), festivalId, request.seatIds());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(result));
    }
}
