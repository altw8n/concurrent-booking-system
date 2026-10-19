package com.example.concurrent_booking_system.exception;

public class SeatNotFoundException extends RuntimeException {

    public SeatNotFoundException(Long seatId, Long eventId) {
        super("Seat " + seatId + " not found for event " + eventId);
    }
}
