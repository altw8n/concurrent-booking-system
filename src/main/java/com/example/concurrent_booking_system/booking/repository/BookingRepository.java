package com.example.concurrent_booking_system.booking.repository;

import com.example.concurrent_booking_system.booking.entity.Booking;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingRepository extends JpaRepository<Booking, Long> {
}