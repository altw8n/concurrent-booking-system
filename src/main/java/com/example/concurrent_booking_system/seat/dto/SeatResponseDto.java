package com.example.concurrent_booking_system.seat.dto;

import com.example.concurrent_booking_system.seat.SeatStatus;

import java.math.BigDecimal;

public record SeatResponseDto(
        Long id,
        String rowNumber,
        Integer seatNumber,
        SeatStatus status,
        BigDecimal price
) {
}
