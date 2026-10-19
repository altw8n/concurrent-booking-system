package com.example.concurrent_booking_system.booking.dto;

import com.example.concurrent_booking_system.booking.BookingStatus;

import java.time.OffsetDateTime;

public record BookingResponseDto(
        Long id,
        Long userId,
        Long seatId,
        BookingStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt
) {
}
