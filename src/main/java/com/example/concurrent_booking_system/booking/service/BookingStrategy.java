package com.example.concurrent_booking_system.booking.service;

import com.example.concurrent_booking_system.booking.dto.BookingResponseDto;

/** One complete booking attempt; implementations own the transaction and do not retry internally. */
public interface BookingStrategy {
    BookingResponseDto bookSeat(Long eventId, Long seatId, Long userId);
}
