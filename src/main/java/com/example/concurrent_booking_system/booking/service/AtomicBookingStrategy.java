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
import com.example.concurrent_booking_system.seat.repository.SeatRepository;
import com.example.concurrent_booking_system.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
public class AtomicBookingStrategy implements BookingStrategy {

    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final SeatRepository seatRepository;
    private final BookingRepository bookingRepository;

    public AtomicBookingStrategy(EventRepository eventRepository, UserRepository userRepository,
                                 SeatRepository seatRepository, BookingRepository bookingRepository) {
        this.eventRepository = eventRepository;
        this.userRepository = userRepository;
        this.seatRepository = seatRepository;
        this.bookingRepository = bookingRepository;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
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

        // PostgreSQL checks FREE as part of the UPDATE; a previous Java-side read is not authoritative.
        if (seatRepository.bookIfFree(eventId, seatId) == 0) {
            seatRepository.findById(seatId)
                    .filter(seat -> eventId.equals(seat.getEventId()))
                    .orElseThrow(() -> new SeatNotFoundException(seatId, eventId));
            throw new SeatAlreadyBookedException(seatId);
        }

        // The seat UPDATE and this INSERT commit or roll back together.
        Booking savedBooking = bookingRepository.save(
                Booking.createActive(userId, seatId, OffsetDateTime.now(ZoneOffset.UTC)));
        return new BookingResponseDto(
                savedBooking.getId(), savedBooking.getUserId(), savedBooking.getSeatId(),
                savedBooking.getStatus(), savedBooking.getCreatedAt(), savedBooking.getExpiresAt()
        );
    }
}
