package com.example.concurrent_booking_system.exception;

public class BookingCancellationTimeoutException extends RuntimeException {
    public BookingCancellationTimeoutException(Long bookingId, int timeoutMs, Throwable cause) {
        super("Could not cancel booking " + bookingId + "; lock wait exceeded " + timeoutMs + " ms", cause);
    }
}
