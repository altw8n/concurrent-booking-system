package com.example.concurrent_booking_system.booking.repository;

import com.example.concurrent_booking_system.booking.entity.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    List<Booking> findAllByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE bookings SET status = 'CANCELLED'
            WHERE id = :bookingId AND status = 'ACTIVE'
            """, nativeQuery = true)
    int cancelIfActive(@Param("bookingId") Long bookingId);
}
