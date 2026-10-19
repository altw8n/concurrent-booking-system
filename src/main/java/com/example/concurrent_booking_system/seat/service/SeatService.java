package com.example.concurrent_booking_system.seat.service;

import com.example.concurrent_booking_system.event.repository.EventRepository;
import com.example.concurrent_booking_system.exception.EventNotFoundException;
import com.example.concurrent_booking_system.seat.dto.SeatResponseDto;
import com.example.concurrent_booking_system.seat.entity.Seat;
import com.example.concurrent_booking_system.seat.repository.SeatRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class SeatService {
    private final SeatRepository seatRepository;
    private final EventRepository eventRepository;

    public SeatService(SeatRepository seatRepository, EventRepository eventRepository) {
        this.seatRepository = seatRepository;
        this.eventRepository = eventRepository;
    }

    private SeatResponseDto toResponse(Seat seat) {
        return new SeatResponseDto(
                seat.getId(),
                seat.getRowNumber(),
                seat.getSeatNumber(),
                seat.getStatus(),
                seat.getPrice()
        );
    }

    @Transactional(readOnly = true)
    public List<SeatResponseDto> getSeatsByEventId(Long eventId) {
        if (!eventRepository.existsById(eventId)){
            throw new EventNotFoundException(eventId);
        }
        return seatRepository.findAllByEventId(eventId).stream().map(this::toResponse).toList();
    }

}
