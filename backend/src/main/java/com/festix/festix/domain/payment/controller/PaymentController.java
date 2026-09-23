package com.festix.festix.domain.payment.controller;

import com.festix.festix.common.response.ApiResponse;
import com.festix.festix.domain.payment.dto.PaymentResponse;
import com.festix.festix.domain.payment.entity.Payment;
import com.festix.festix.domain.payment.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/api/reservations/{reservationId}/payments")
    public ResponseEntity<ApiResponse<PaymentResponse>> startPayment(@PathVariable Long reservationId) {
        Payment payment = paymentService.startPayment(reservationId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(PaymentResponse.from(payment)));
    }

    /**
     * {@code confirmPayment} commits its PENDING -> CONFIRMING phase before
     * running CONFIRMING -> COMPLETED. If the second phase fails, this
     * endpoint returns an error response, but the payment has already
     * durably advanced to CONFIRMING server-side — it is not rolled back.
     * That's expected: the stuck-CONFIRMING safety-net batch resolves it
     * later, so an error here does not mean nothing happened.
     */
    @PostMapping("/api/payments/{paymentId}/confirm")
    public ApiResponse<PaymentResponse> confirmPayment(@PathVariable Long paymentId) {
        paymentService.confirmPayment(paymentId);
        Payment payment = paymentService.getPayment(paymentId);
        return ApiResponse.success(PaymentResponse.from(payment));
    }

    @PostMapping("/api/payments/{paymentId}/refund")
    public ApiResponse<PaymentResponse> refundPayment(@PathVariable Long paymentId) {
        paymentService.refundPayment(paymentId);
        Payment payment = paymentService.getPayment(paymentId);
        return ApiResponse.success(PaymentResponse.from(payment));
    }
}
