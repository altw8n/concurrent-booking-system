package com.example.concurrent_booking_system.booking.service;

import com.example.concurrent_booking_system.booking.BookingStatus;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BookingServiceTest {

    private final EventRepository eventRepository = mock(EventRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final SeatRepository seatRepository = mock(SeatRepository.class);
    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final Seat seat = mock(Seat.class);

    private BookingService bookingService;

    @BeforeEach
    void setUp() {
        bookingService = new BookingService(eventRepository, userRepository, seatRepository, bookingRepository);
    }

    @Test
    void createsActiveBookingAndRejectsASequentialAttemptForBookedSeat() {
        when(eventRepository.existsById(1L)).thenReturn(true);
        when(userRepository.existsById(2L)).thenReturn(true);
        when(seatRepository.findById(3L)).thenReturn(Optional.of(seat));
        when(seat.getEventId()).thenReturn(1L);
        when(seat.getStatus()).thenReturn(SeatStatus.FREE, SeatStatus.BOOKED);
        when(bookingRepository.save(any(Booking.class))).thenAnswer(invocation -> invocation.getArgument(0));

        BookingResponseDto response = bookingService.bookSeat(1L, 3L, 2L);

        assertEquals(2L, response.userId());
        assertEquals(3L, response.seatId());
        assertEquals(BookingStatus.ACTIVE, response.status());
        assertNotNull(response.createdAt());
        assertNull(response.expiresAt());
        assertThrows(SeatAlreadyBookedException.class, () -> bookingService.bookSeat(1L, 3L, 2L));
        verify(bookingRepository, times(1)).save(any(Booking.class));
        verify(seat, times(1)).markBooked();
    }

    @Test
    void rejectsMissingUserIdBeforeAccessingRepositories() {
        assertThrows(InvalidBookingRequestException.class, () -> bookingService.bookSeat(1L, 3L, null));
        verify(eventRepository, never()).existsById(any());
        verify(bookingRepository, never()).save(any(Booking.class));
    }

    @Test
    void rejectsNonPositiveUserId() {
        assertThrows(InvalidBookingRequestException.class, () -> bookingService.bookSeat(1L, 3L, 0L));
        verify(bookingRepository, never()).save(any(Booking.class));
    }

    @Test
    void rejectsMissingEvent() {
        when(eventRepository.existsById(1L)).thenReturn(false);

        assertThrows(EventNotFoundException.class, () -> bookingService.bookSeat(1L, 3L, 2L));
        verify(bookingRepository, never()).save(any(Booking.class));
    }

    @Test
    void rejectsMissingUser() {
        when(eventRepository.existsById(1L)).thenReturn(true);
        when(userRepository.existsById(2L)).thenReturn(false);

        assertThrows(UserNotFoundException.class, () -> bookingService.bookSeat(1L, 3L, 2L));
        verify(bookingRepository, never()).save(any(Booking.class));
    }

    @Test
    void rejectsMissingSeat() {
        when(eventRepository.existsById(1L)).thenReturn(true);
        when(userRepository.existsById(2L)).thenReturn(true);
        when(seatRepository.findById(3L)).thenReturn(Optional.empty());

        assertThrows(SeatNotFoundException.class, () -> bookingService.bookSeat(1L, 3L, 2L));
        verify(bookingRepository, never()).save(any(Booking.class));
    }

    @Test
    void rejectsSeatBelongingToAnotherEvent() {
        when(eventRepository.existsById(1L)).thenReturn(true);
        when(userRepository.existsById(2L)).thenReturn(true);
        when(seatRepository.findById(3L)).thenReturn(Optional.of(seat));
        when(seat.getEventId()).thenReturn(4L);

        assertThrows(SeatNotFoundException.class, () -> bookingService.bookSeat(1L, 3L, 2L));
        verify(bookingRepository, never()).save(any(Booking.class));
        verify(seat, never()).markBooked();
    }
}
