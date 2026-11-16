package com.example.concurrent_booking_system.exception;

public class BookingSerializationException extends RuntimeException {

    public BookingSerializationException(Long seatId, int attempts, Throwable cause) {
        super("Could not serialize booking for seat " + seatId + " after " + attempts + " attempt(s)", cause);
    }
}
