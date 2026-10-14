package com.example.concurrent_booking_system.seat.controller;

import com.example.concurrent_booking_system.seat.dto.SeatResponseDto;
import com.example.concurrent_booking_system.seat.service.SeatService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/events/{eventId}/seats")
public class SeatController {
    private final SeatService seatService;

    public SeatController(SeatService seatService) {
        this.seatService = seatService;
    }

    @GetMapping
    public List<SeatResponseDto> getSeatsByEventId(@PathVariable Long eventId) {
        return seatService.getSeatsByEventId(eventId);
    }
}
