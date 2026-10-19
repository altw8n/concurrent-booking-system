package com.example.concurrent_booking_system.booking.entity;

import com.example.concurrent_booking_system.booking.BookingStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "bookings")
@Getter
public class Booking {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "seat_id", nullable = false)
    private Long seatId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private BookingStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    public static Booking createActive(
            Long userId,
            Long seatId,
            OffsetDateTime createdAt
    ) {
        Booking booking = new Booking();
        booking.userId = userId;
        booking.seatId = seatId;
        booking.status = BookingStatus.ACTIVE;
        booking.createdAt = createdAt;
        return booking;
    }
}
