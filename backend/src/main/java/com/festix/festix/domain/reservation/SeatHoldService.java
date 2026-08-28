package com.festix.festix.domain.reservation;

import com.festix.festix.domain.seat.Seat;
import com.festix.festix.domain.seat.SeatFestivalMismatchException;
import com.festix.festix.domain.seat.SeatNotFoundException;
import com.festix.festix.domain.seat.SeatRepository;
import com.festix.festix.domain.seat.SeatStatus;
import com.festix.festix.domain.seat.SeatUnavailableException;
import com.festix.festix.domain.user.User;
import com.festix.festix.domain.user.UserRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SeatHoldService {

    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final UserRepository userRepository;
    private final ReservationProperties reservationProperties;

    /**
     * Locks every requested seat (sorted ascending, acquired sequentially to
     * prevent deadlocks), validates all of them, then flips them to HELD and
     * creates the reservation bundle. Runs as a single transaction: the
     * pessimistic locks must stay held from the first seat check through the
     * reservation insert, so no step here may run in its own transaction.
     */
    @Transactional
    public ReservationHoldResult holdSeats(Long userId, Long festivalId, List<Long> seatIds) {
        List<Long> sortedSeatIds = seatIds.stream().distinct().sorted().toList();

        List<Seat> lockedSeats = new ArrayList<>(sortedSeatIds.size());
        for (Long seatId : sortedSeatIds) {
            Seat seat = seatRepository.findByIdForUpdate(seatId, reservationProperties.lockTimeoutMillis())
                    .orElseThrow(() -> new SeatNotFoundException(seatId));

            if (!seat.getFestival().getId().equals(festivalId)) {
                throw new SeatFestivalMismatchException(seatId, festivalId);
            }
            if (seat.getStatus() != SeatStatus.AVAILABLE) {
                throw new SeatUnavailableException(seatId, seat.getStatus());
            }
            lockedSeats.add(seat);
        }

        for (Seat seat : lockedSeats) {
            int updated = seatRepository.holdSeat(seat.getId());
            if (updated == 0) {
                // Held the row lock and just observed AVAILABLE above, so this
                // should be unreachable — treat it as an invariant violation.
                throw new SeatUnavailableException(seat.getId(), seat.getStatus());
            }
        }

        User userRef = userRepository.getReferenceById(userId);
        LocalDateTime endTtl = LocalDateTime.now().plusMinutes(reservationProperties.holdTtlMinutes());

        Reservation reservation = Reservation.builder()
                .user(userRef)
                .endTtl(endTtl)
                .build();

        for (Seat seat : lockedSeats) {
            reservation.getItems().add(
                    ReservationItem.builder()
                            .reservation(reservation)
                            .seat(seat)
                            .build());
        }

        Reservation saved = reservationRepository.save(reservation);

        List<Long> heldSeatIds = lockedSeats.stream().map(Seat::getId).toList();
        return new ReservationHoldResult(saved.getId(), heldSeatIds, saved.getEndTtl());
    }
}
