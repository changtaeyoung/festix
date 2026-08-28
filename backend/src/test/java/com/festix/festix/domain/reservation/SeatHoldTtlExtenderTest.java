package com.festix.festix.domain.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.festix.festix.domain.seat.Seat;
import com.festix.festix.redis.SeatHoldRedisWriter;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SeatHoldTtlExtenderTest {

    private static final long HOLD_TTL_MINUTES = 5L;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReservationItemRepository reservationItemRepository;

    @Mock
    private SeatHoldRedisWriter seatHoldRedisWriter;

    private SeatHoldTtlExtender extender;

    @BeforeEach
    void setUp() {
        extender = new SeatHoldTtlExtender(
                reservationRepository,
                reservationItemRepository,
                seatHoldRedisWriter,
                new ReservationProperties(HOLD_TTL_MINUTES, 3000L));
    }

    private void givenReservation(long reservationId, boolean extended) {
        Reservation reservation = Reservation.builder()
                .endTtl(LocalDateTime.now())
                .isExtended(extended)
                .build();
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(reservation));
    }

    private void givenSeats(long reservationId, Long... seatIds) {
        List<ReservationItem> items = java.util.Arrays.stream(seatIds).map(seatId -> {
            Seat seat = mock(Seat.class);
            when(seat.getId()).thenReturn(seatId);
            ReservationItem item = mock(ReservationItem.class);
            when(item.getSeat()).thenReturn(seat);
            return item;
        }).toList();
        when(reservationItemRepository.findByReservationId(reservationId)).thenReturn(items);
    }

    @Test
    void resetsRedisBeforePostgres_whenNotYetExtended() {
        givenReservation(1L, false);
        givenSeats(1L, 10L, 20L);
        when(reservationRepository.extendTtl(eq(1L), any())).thenReturn(1);

        extender.extendOnPaymentStart(1L);

        InOrder inOrder = Mockito.inOrder(seatHoldRedisWriter, reservationRepository);
        inOrder.verify(seatHoldRedisWriter).resetHoldTtl(eq(1L), eq(List.of(10L, 20L)), any());
        inOrder.verify(reservationRepository).extendTtl(eq(1L), any());
    }

    @Test
    void resetsToAFullHoldDurationFromNow() {
        givenReservation(1L, false);
        givenSeats(1L, 10L);
        when(reservationRepository.extendTtl(eq(1L), any())).thenReturn(1);

        LocalDateTime before = LocalDateTime.now();
        extender.extendOnPaymentStart(1L);
        LocalDateTime after = LocalDateTime.now();

        ArgumentCaptor<LocalDateTime> newEndTtl = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(seatHoldRedisWriter).resetHoldTtl(eq(1L), any(), newEndTtl.capture());
        assertThat(newEndTtl.getValue())
                .isAfterOrEqualTo(before.plusMinutes(HOLD_TTL_MINUTES))
                .isBeforeOrEqualTo(after.plusMinutes(HOLD_TTL_MINUTES));
    }

    @Test
    void passesSeatIdsAscending() {
        givenReservation(1L, false);
        givenSeats(1L, 30L, 10L, 20L);
        when(reservationRepository.extendTtl(eq(1L), any())).thenReturn(1);

        extender.extendOnPaymentStart(1L);

        verify(seatHoldRedisWriter).resetHoldTtl(eq(1L), eq(List.of(10L, 20L, 30L)), any());
    }

    @Test
    void isNoOp_whenAlreadyExtended() {
        givenReservation(1L, true);

        extender.extendOnPaymentStart(1L);

        verifyNoInteractions(seatHoldRedisWriter);
        verify(reservationRepository, never()).extendTtl(any(), any());
        verifyNoInteractions(reservationItemRepository);
    }

    @Test
    void doesNotTouchPostgres_whenRedisResetFails() {
        givenReservation(1L, false);
        givenSeats(1L, 10L);
        doThrow(new SeatHoldTtlExtensionException(1L, 10L, "Redis hold key missing"))
                .when(seatHoldRedisWriter).resetHoldTtl(eq(1L), any(), any());

        assertThatThrownBy(() -> extender.extendOnPaymentStart(1L))
                .isInstanceOf(SeatHoldTtlExtensionException.class);

        verify(reservationRepository, never()).extendTtl(any(), any());
    }

    @Test
    void toleratesZeroRowUpdate_fromConcurrentExtension() {
        givenReservation(1L, false);
        givenSeats(1L, 10L);
        when(reservationRepository.extendTtl(eq(1L), any())).thenReturn(0);

        extender.extendOnPaymentStart(1L);

        verify(seatHoldRedisWriter).resetHoldTtl(eq(1L), any(), any());
    }

    @Test
    void throwsReservationNotFound_whenReservationMissing() {
        when(reservationRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> extender.extendOnPaymentStart(99L))
                .isInstanceOf(ReservationNotFoundException.class);

        verifyNoInteractions(seatHoldRedisWriter);
    }
}
