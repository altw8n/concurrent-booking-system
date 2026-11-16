package com.example.concurrent_booking_system.booking.service;

import com.example.concurrent_booking_system.booking.BookingStatus;
import com.example.concurrent_booking_system.booking.entity.Booking;
import com.example.concurrent_booking_system.booking.repository.BookingRepository;
import com.example.concurrent_booking_system.event.repository.EventRepository;
import com.example.concurrent_booking_system.exception.SeatAlreadyBookedException;
import com.example.concurrent_booking_system.exception.SeatNotFoundException;
import com.example.concurrent_booking_system.seat.entity.Seat;
import com.example.concurrent_booking_system.seat.repository.SeatRepository;
import com.example.concurrent_booking_system.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AtomicBookingStrategyTest {
    private final EventRepository events = mock(EventRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final SeatRepository seats = mock(SeatRepository.class);
    private final BookingRepository bookings = mock(BookingRepository.class);
    private final AtomicBookingStrategy strategy = new AtomicBookingStrategy(events, users, seats, bookings);

    @BeforeEach
    void prepareValidRequest() {
        when(events.existsById(1L)).thenReturn(true);
        when(users.existsById(2L)).thenReturn(true);
    }

    @Test
    void createsActiveBookingAfterSuccessfulClaim() {
        when(seats.bookIfFree(1L, 3L)).thenReturn(1);
        when(bookings.save(any(Booking.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = strategy.bookSeat(1L, 3L, 2L);

        assertEquals(2L, result.userId());
        assertEquals(3L, result.seatId());
        assertEquals(BookingStatus.ACTIVE, result.status());
        assertNotNull(result.createdAt());
        assertNull(result.expiresAt());
    }

    @Test
    void rejectsLostClaimWithoutCreatingBooking() {
        Seat seat = mock(Seat.class);
        when(seat.getEventId()).thenReturn(1L);
        when(seats.bookIfFree(1L, 3L)).thenReturn(0);
        when(seats.findById(3L)).thenReturn(Optional.of(seat));

        assertThrows(SeatAlreadyBookedException.class, () -> strategy.bookSeat(1L, 3L, 2L));
        verifyNoInteractions(bookings);
    }

    @Test
    void distinguishesMissingAndForeignEventSeatsFromLostClaim() {
        Seat foreignSeat = mock(Seat.class);
        when(foreignSeat.getEventId()).thenReturn(9L);
        when(seats.bookIfFree(1L, 3L)).thenReturn(0);
        when(seats.findById(3L)).thenReturn(Optional.empty()).thenReturn(Optional.of(foreignSeat));

        assertThrows(SeatNotFoundException.class, () -> strategy.bookSeat(1L, 3L, 2L));
        assertThrows(SeatNotFoundException.class, () -> strategy.bookSeat(1L, 3L, 2L));
        verifyNoInteractions(bookings);
    }
}
