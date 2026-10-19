package com.example.concurrent_booking_system.exception;

public class SeatAlreadyBookedException extends RuntimeException {

    public SeatAlreadyBookedException(Long seatId) {
        super("Seat already booked: " + seatId);
    }
}
