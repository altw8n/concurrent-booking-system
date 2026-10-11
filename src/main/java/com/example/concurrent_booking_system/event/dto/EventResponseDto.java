package com.example.concurrent_booking_system.event.dto;

import java.time.OffsetDateTime;

public record EventResponseDto(
        Long id,
        String name,
        String description,
        OffsetDateTime startTime
){}
