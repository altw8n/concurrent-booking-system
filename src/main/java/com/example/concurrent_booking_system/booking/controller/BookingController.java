package com.example.concurrent_booking_system.booking.controller;

import com.example.concurrent_booking_system.booking.dto.BookingResponseDto;
import com.example.concurrent_booking_system.booking.dto.CreateBookingRequest;
import com.example.concurrent_booking_system.booking.service.BookingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events/{eventId}/seats/{seatId}/book")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping
    public ResponseEntity<BookingResponseDto> bookSeat(
            @PathVariable("eventId") Long eventId,
            @PathVariable("seatId") Long seatId,
            @RequestBody CreateBookingRequest request
    ) {
        BookingResponseDto response = bookingService.bookSeat(eventId, seatId, request.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
