package com.festix.festix.domain.seat.repository;

import com.festix.festix.domain.seat.entity.Seat;
import com.festix.festix.domain.seat.entity.SeatStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeatRepository extends JpaRepository<Seat, Long>, SeatLockRepository {

    @Modifying(clearAutomatically = true)
    @Query("update Seat s set s.status = com.festix.festix.domain.seat.entity.SeatStatus.HELD "
            + "where s.id = :id and s.status = com.festix.festix.domain.seat.entity.SeatStatus.AVAILABLE")
    int holdSeat(@Param("id") Long id);

    @Modifying(clearAutomatically = true)
    @Query("update Seat s set s.status = com.festix.festix.domain.seat.entity.SeatStatus.AVAILABLE "
            + "where s.id = :id and s.status = :fromStatus")
    int releaseSeat(@Param("id") Long id, @Param("fromStatus") SeatStatus fromStatus);

    @Modifying(clearAutomatically = true)
    @Query("update Seat s set s.status = com.festix.festix.domain.seat.entity.SeatStatus.SOLD "
            + "where s.id = :id and s.status = com.festix.festix.domain.seat.entity.SeatStatus.HELD")
    int sellSeat(@Param("id") Long id);
}
