package com.example.concurrent_booking_system.exception;

public class BookingAccessDeniedException extends RuntimeException {
    public BookingAccessDeniedException(Long bookingId) {
        super("Booking " + bookingId + " belongs to another user");
    }
}
