package com.festix.festix.domain.reservation;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, Long> {

    List<Reservation> findByUserId(Long userId);

    /**
     * Seats still HELD whose owning reservation's end_ttl has passed. Scoped
     * to each seat's most recent reservation_item (MAX(reservation.id) per
     * seat) so an old, already-expired reservation can't match a seat that
     * has since been re-held by a newer, still-valid reservation —
     * reservation_item is append-only history, not a single current-state row.
     */
    @Query("select i.seat.id from ReservationItem i "
            + "where i.reservation.endTtl < :now "
            + "and i.seat.status = com.festix.festix.domain.seat.SeatStatus.HELD "
            + "and i.reservation.id = ("
            + "  select max(i2.reservation.id) from ReservationItem i2 where i2.seat.id = i.seat.id"
            + ")")
    List<Long> findExpiredHeldSeatIds(@Param("now") LocalDateTime now);
}
