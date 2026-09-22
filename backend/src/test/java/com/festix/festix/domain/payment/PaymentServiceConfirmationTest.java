package com.festix.festix.domain.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.festix.festix.domain.payment.entity.Payment;
import com.festix.festix.domain.payment.entity.PaymentStatus;
import com.festix.festix.domain.payment.exception.PaymentNotFoundException;
import com.festix.festix.domain.payment.exception.PaymentStateConflictException;
import com.festix.festix.domain.payment.repository.PaymentRepository;
import com.festix.festix.domain.payment.service.PaymentService;
import com.festix.festix.domain.reservation.entity.Reservation;
import com.festix.festix.domain.reservation.entity.ReservationItem;
import com.festix.festix.domain.reservation.repository.ReservationItemRepository;
import com.festix.festix.domain.reservation.repository.ReservationRepository;
import com.festix.festix.domain.reservation.service.SeatHoldTtlExtender;
import com.festix.festix.domain.seat.entity.Seat;
import com.festix.festix.domain.seat.repository.SeatRepository;
import com.festix.festix.domain.seat.entity.SeatStatus;
import com.festix.festix.domain.seat.exception.SeatUnavailableException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PaymentServiceConfirmationTest {

    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private ReservationItemRepository reservationItemRepository;
    @Mock
    private SeatRepository seatRepository;
    @Mock
    private SeatHoldTtlExtender seatHoldTtlExtender;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentService = new PaymentService(
                paymentRepository,
                reservationRepository,
                reservationItemRepository,
                seatRepository,
                seatHoldTtlExtender);
    }

    private static Payment paymentWithStatus(PaymentStatus status) {
        return Payment.builder().amount(BigDecimal.TEN).status(status).build();
    }

    private static Payment paymentForReservation(long reservationId) {
        return Payment.builder()
                .amount(BigDecimal.TEN)
                .status(PaymentStatus.CONFIRMING)
                .reservation(Reservation.builder().id(reservationId).build())
                .build();
    }

    private static ReservationItem itemForSeat(long seatId) {
        return ReservationItem.builder().seat(Seat.builder().id(seatId).build()).build();
    }

    @Test
    void confirmPayment_invokesBeginThenCompleteInOrder() {
        PaymentService self = mock(PaymentService.class);
        ReflectionTestUtils.setField(paymentService, "self", self);

        paymentService.confirmPayment(7L);

        InOrder inOrder = Mockito.inOrder(self);
        inOrder.verify(self).beginConfirm(7L);
        inOrder.verify(self).completeConfirmation(7L);
    }

    @Test
    void beginConfirm_returns_whenOneRowFlipped() {
        when(paymentRepository.beginConfirm(1L)).thenReturn(1);

        assertThatCode(() -> paymentService.beginConfirm(1L)).doesNotThrowAnyException();

        verify(paymentRepository, never()).findById(anyLong());
    }

    @Test
    void beginConfirm_throwsConflict_whenPaymentNoLongerPending() {
        when(paymentRepository.beginConfirm(1L)).thenReturn(0);
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(paymentWithStatus(PaymentStatus.COMPLETED)));

        assertThatThrownBy(() -> paymentService.beginConfirm(1L))
                .isInstanceOf(PaymentStateConflictException.class)
                .hasMessageContaining("not PENDING")
                .hasMessageContaining("COMPLETED");
    }

    @Test
    void beginConfirm_throwsNotFound_whenPaymentGone() {
        when(paymentRepository.beginConfirm(1L)).thenReturn(0);
        when(paymentRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.beginConfirm(1L))
                .isInstanceOf(PaymentNotFoundException.class);
    }

    @Test
    void completeConfirmation_throwsConflict_whenPaymentNotConfirming() {
        when(paymentRepository.findById(1L))
                .thenReturn(Optional.of(paymentForReservation(100L)))
                .thenReturn(Optional.of(paymentWithStatus(PaymentStatus.PENDING)));
        when(paymentRepository.confirmPayment(eq(1L), any())).thenReturn(0);

        assertThatThrownBy(() -> paymentService.completeConfirmation(1L))
                .isInstanceOf(PaymentStateConflictException.class)
                .hasMessageContaining("not CONFIRMING");

        verify(seatRepository, never()).sellSeat(anyLong());
    }

    @Test
    void completeConfirmation_sellsEverySeatAscending_onHappyPath() {
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(paymentForReservation(100L)));
        when(paymentRepository.confirmPayment(eq(1L), any())).thenReturn(1);
        when(reservationItemRepository.findByReservationId(100L))
                .thenReturn(List.of(itemForSeat(30L), itemForSeat(10L), itemForSeat(20L)));
        when(seatRepository.sellSeat(anyLong())).thenReturn(1);

        paymentService.completeConfirmation(1L);

        InOrder inOrder = Mockito.inOrder(seatRepository);
        inOrder.verify(seatRepository).sellSeat(10L);
        inOrder.verify(seatRepository).sellSeat(20L);
        inOrder.verify(seatRepository).sellSeat(30L);
    }

    @Test
    void completeConfirmation_throwsSeatUnavailable_whenASeatIsNoLongerHeld() {
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(paymentForReservation(100L)));
        when(paymentRepository.confirmPayment(eq(1L), any())).thenReturn(1);
        when(reservationItemRepository.findByReservationId(100L))
                .thenReturn(List.of(itemForSeat(10L), itemForSeat(20L)));
        when(seatRepository.sellSeat(10L)).thenReturn(0);
        when(seatRepository.findById(10L))
                .thenReturn(Optional.of(Seat.builder().id(10L).status(SeatStatus.AVAILABLE).build()));

        assertThatThrownBy(() -> paymentService.completeConfirmation(1L))
                .isInstanceOf(SeatUnavailableException.class);

        verify(seatRepository, never()).sellSeat(20L);
    }
}
