package com.example.concurrent_booking_system.booking.controller;

import com.example.concurrent_booking_system.booking.dto.BookingResponseDto;
import com.example.concurrent_booking_system.booking.service.BookingManagementService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class BookingManagementController {

    private final BookingManagementService bookingManagementService;

    public BookingManagementController(BookingManagementService bookingManagementService) {
        this.bookingManagementService = bookingManagementService;
    }

    @GetMapping("/users/{userId}/bookings")
    public List<BookingResponseDto> getUserBookings(@PathVariable("userId") Long userId) {
        return bookingManagementService.getUserBookings(userId);
    }

    @DeleteMapping("/bookings/{bookingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelBooking(@PathVariable("bookingId") Long bookingId,
                              @RequestParam("userId") Long userId) {
        bookingManagementService.cancelBooking(bookingId, userId);
    }
}
