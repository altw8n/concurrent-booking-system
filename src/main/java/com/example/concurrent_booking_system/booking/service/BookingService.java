package com.example.concurrent_booking_system.booking.service;

import com.example.concurrent_booking_system.booking.dto.BookingResponseDto;
import com.example.concurrent_booking_system.booking.entity.Booking;
import com.example.concurrent_booking_system.booking.repository.BookingRepository;
import com.example.concurrent_booking_system.event.repository.EventRepository;
import com.example.concurrent_booking_system.exception.EventNotFoundException;
import com.example.concurrent_booking_system.exception.InvalidBookingRequestException;
import com.example.concurrent_booking_system.exception.SeatAlreadyBookedException;
import com.example.concurrent_booking_system.exception.SeatNotFoundException;
import com.example.concurrent_booking_system.exception.UserNotFoundException;
import com.example.concurrent_booking_system.seat.SeatStatus;
import com.example.concurrent_booking_system.seat.entity.Seat;
import com.example.concurrent_booking_system.seat.repository.SeatRepository;
import com.example.concurrent_booking_system.user.repository.UserRepository;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
@AllArgsConstructor
public class BookingService {

    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final SeatRepository seatRepository;
    private final BookingRepository bookingRepository;

    @Transactional
    public BookingResponseDto bookSeat(Long eventId, Long seatId, Long userId) {
        if (userId == null || userId <= 0) {
            throw new InvalidBookingRequestException("userId must be a positive number");
        }
        if (!eventRepository.existsById(eventId)) {
            throw new EventNotFoundException(eventId);
        }
        if (!userRepository.existsById(userId)) {
            throw new UserNotFoundException(userId);
        }
        Seat seat = seatRepository.findById(seatId)
                .filter(foundSeat -> eventId.equals(foundSeat.getEventId()))
                .orElseThrow(() -> new SeatNotFoundException(seatId, eventId));

        if (seat.getStatus() != SeatStatus.FREE) {
            throw new SeatAlreadyBookedException(seatId);
        }
        Booking booking = Booking.createActive(userId, seatId, OffsetDateTime.now(ZoneOffset.UTC));
        Booking savedBooking = bookingRepository.save(booking);
        seat.markBooked();
        return new BookingResponseDto(
                savedBooking.getId(),
                savedBooking.getUserId(),
                savedBooking.getSeatId(),
                savedBooking.getStatus(),
                savedBooking.getCreatedAt(),
                savedBooking.getExpiresAt()
        );
    }
}
