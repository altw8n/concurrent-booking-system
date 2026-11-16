package com.example.concurrent_booking_system.seat.repository;

import com.example.concurrent_booking_system.seat.entity.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface SeatRepository extends JpaRepository<Seat, Long> {
    List<Seat> findAllByEventId(Long eventId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select seat from Seat seat where seat.id = :seatId")
    Optional<Seat> findByIdForUpdate(@Param("seatId") Long seatId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE seats
            SET status = 'BOOKED'
            WHERE id = :seatId
              AND event_id = :eventId
              AND status = 'FREE'
            """, nativeQuery = true)
    int bookIfFree(@Param("eventId") Long eventId, @Param("seatId") Long seatId);
}
