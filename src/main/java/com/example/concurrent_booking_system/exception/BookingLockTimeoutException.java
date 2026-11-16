package com.example.concurrent_booking_system.exception;

public class BookingLockTimeoutException extends RuntimeException {

    public BookingLockTimeoutException(Long seatId, int timeoutMs, Throwable cause) {
        super("Could not lock seat " + seatId + " within " + timeoutMs + " ms", cause);
    }
}
