package com.example.concurrent_booking_system.seat.repository;

import com.example.concurrent_booking_system.seat.entity.Seat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SeatRepository extends JpaRepository<Seat, Long> {
    List<Seat> findAllByEventId(Long eventId);
}