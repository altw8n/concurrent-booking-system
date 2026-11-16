package com.example.concurrent_booking_system.exception;

public class BookingConflictException extends RuntimeException {

    public BookingConflictException(Long seatId, int attempts, Throwable cause) {
        super("Seat " + seatId + " was concurrently modified; booking failed after " + attempts + " attempt(s)", cause);
    }
}
