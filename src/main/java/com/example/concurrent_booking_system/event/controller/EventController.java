package com.example.concurrent_booking_system.event.controller;

import com.example.concurrent_booking_system.event.dto.EventResponseDto;
import com.example.concurrent_booking_system.event.service.EventService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/events")
public class EventController {
    private final EventService eventService;

    public EventController(EventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping
    public List<EventResponseDto> getEvents() {
        return eventService.getEvents();
    }

    @GetMapping("/{eventId}")
    public EventResponseDto getEvent(@PathVariable("eventId") Long eventId) {
        return eventService.getEventById(eventId);
    }
}
