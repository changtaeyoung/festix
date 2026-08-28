package com.festix.festix.domain.reservation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationItemRepository extends JpaRepository<ReservationItem, Long> {

    List<ReservationItem> findByReservationId(Long reservationId);

    /**
     * The seat's most recent reservation (MAX(reservation.id)), same scoping
     * as ReservationRepository#findExpiredHeldSeatIds — reservation_item is
     * append-only history, so a seat can have rows from earlier, already-
     * resolved holds.
     */
    @Query("select i.reservation.id from ReservationItem i "
            + "where i.seat.id = :seatId "
            + "and i.reservation.id = ("
            + "  select max(i2.reservation.id) from ReservationItem i2 where i2.seat.id = :seatId"
            + ")")
    Optional<Long> findLatestReservationIdBySeatId(@Param("seatId") Long seatId);
}
