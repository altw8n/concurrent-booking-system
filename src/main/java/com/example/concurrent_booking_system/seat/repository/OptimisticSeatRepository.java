package com.example.concurrent_booking_system.seat.repository;

import com.example.concurrent_booking_system.seat.entity.OptimisticSeat;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OptimisticSeatRepository extends JpaRepository<OptimisticSeat, Long> {
}
