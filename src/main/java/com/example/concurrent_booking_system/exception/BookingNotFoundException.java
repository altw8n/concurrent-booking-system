package com.example.concurrent_booking_system.exception;

public class BookingNotFoundException extends RuntimeException {
    public BookingNotFoundException(Long bookingId) {
        super("Booking " + bookingId + " not found");
    }
}
