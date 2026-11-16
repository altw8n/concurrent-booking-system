package com.example.concurrent_booking_system.booking.service;

import com.example.concurrent_booking_system.booking.BookingStatus;
import com.example.concurrent_booking_system.booking.entity.Booking;
import com.example.concurrent_booking_system.booking.repository.BookingRepository;
import com.example.concurrent_booking_system.exception.BookingAccessDeniedException;
import com.example.concurrent_booking_system.exception.BookingCancellationTimeoutException;
import com.example.concurrent_booking_system.exception.BookingNotFoundException;
import com.example.concurrent_booking_system.exception.InvalidBookingRequestException;
import com.example.concurrent_booking_system.exception.UserNotFoundException;
import com.example.concurrent_booking_system.seat.entity.Seat;
import com.example.concurrent_booking_system.seat.repository.SeatRepository;
import com.example.concurrent_booking_system.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookingManagementServiceTest {

    private final BookingRepository bookings = mock(BookingRepository.class);
    private final SeatRepository seats = mock(SeatRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final BookingManagementService service = new BookingManagementService(bookings, seats, users, jdbc, 2000);

    @Test
    void rejectsInvalidIdsBeforeAccessingRepositories() {
        assertThrows(InvalidBookingRequestException.class, () -> service.getUserBookings(null));
        assertThrows(InvalidBookingRequestException.class, () -> service.getUserBookings(0L));
        assertThrows(InvalidBookingRequestException.class, () -> service.cancelBooking(null, 1L));
        assertThrows(InvalidBookingRequestException.class, () -> service.cancelBooking(-1L, 1L));
        assertThrows(InvalidBookingRequestException.class, () -> service.cancelBooking(5L, -1L));
        verifyNoInteractions(bookings, seats, users, jdbc);
    }

    @Test
    void distinguishesMissingUserAndBookingWithoutWriting() {
        assertThrows(UserNotFoundException.class, () -> service.getUserBookings(1L));
        assertThrows(UserNotFoundException.class, () -> service.cancelBooking(5L, 1L));
        when(users.existsById(1L)).thenReturn(true);
        assertThrows(BookingNotFoundException.class, () -> service.cancelBooking(5L, 1L));
        verifyNoInteractions(seats, jdbc);
        verify(bookings, never()).cancelIfActive(anyLong());
    }

    @Test
    void rejectsAnotherOwnerEvenIfBookingIsAlreadyCancelled() {
        Booking booking = prepareBooking(BookingStatus.CANCELLED);
        when(users.existsById(9L)).thenReturn(true);
        when(booking.getUserId()).thenReturn(1L);

        assertThrows(BookingAccessDeniedException.class, () -> service.cancelBooking(5L, 9L));
        verifyNoInteractions(seats, jdbc);
        verify(bookings, never()).cancelIfActive(anyLong());
    }

    @Test
    void repeatedCancellationDoesNotTouchSeatOrVersion() {
        prepareBooking(BookingStatus.CANCELLED);

        service.cancelBooking(5L, 1L);

        verifyNoInteractions(seats, jdbc);
        verify(bookings, never()).cancelIfActive(anyLong());
    }

    @Test
    void rechecksActiveStatusAfterSeatLockInsteadOfTrustingInitialRead() {
        prepareBooking(BookingStatus.ACTIVE);
        when(seats.findByIdForUpdate(3L)).thenReturn(Optional.of(mock(Seat.class)));
        when(bookings.cancelIfActive(5L)).thenReturn(0);

        service.cancelBooking(5L, 1L);

        verify(seats, never()).releaseIfNoActiveBookings(anyLong());
    }

    @Test
    void locksSeatBeforeCancellingAndReleasingIt() {
        prepareBooking(BookingStatus.ACTIVE);
        when(seats.findByIdForUpdate(3L)).thenReturn(Optional.of(mock(Seat.class)));
        when(bookings.cancelIfActive(5L)).thenReturn(1);

        service.cancelBooking(5L, 1L);

        var order = inOrder(seats, bookings);
        order.verify(bookings).findById(5L);
        order.verify(seats).findByIdForUpdate(3L);
        order.verify(bookings).cancelIfActive(5L);
        order.verify(seats).releaseIfNoActiveBookings(3L);
    }

    @Test
    void classifiesOnlyPostgresLockTimeoutAndPreservesOtherFailures() {
        prepareBooking(BookingStatus.ACTIVE);
        var timeout = new CannotAcquireLockException("Lock failed", new SQLException("Timeout", "55P03"));
        var deadlock = new CannotAcquireLockException("Lock failed", new SQLException("Deadlock", "40P01"));
        when(seats.findByIdForUpdate(3L)).thenThrow(timeout).thenThrow(deadlock);

        var result = assertThrows(BookingCancellationTimeoutException.class, () -> service.cancelBooking(5L, 1L));
        assertSame(timeout, result.getCause());
        assertSame(deadlock, assertThrows(CannotAcquireLockException.class, () -> service.cancelBooking(5L, 1L)));
        verify(bookings, never()).cancelIfActive(anyLong());
    }

    @Test
    void propagatesReleaseFailureWithoutRetry() {
        prepareBooking(BookingStatus.ACTIVE);
        when(seats.findByIdForUpdate(3L)).thenReturn(Optional.of(mock(Seat.class)));
        when(bookings.cancelIfActive(5L)).thenReturn(1);
        var failure = new DataIntegrityViolationException("Seat update rejected");
        when(seats.releaseIfNoActiveBookings(3L)).thenThrow(failure);

        assertSame(failure, assertThrows(DataIntegrityViolationException.class, () -> service.cancelBooking(5L, 1L)));
        verify(bookings).cancelIfActive(5L);
    }

    @Test
    void rejectsNonPositiveLockTimeout() {
        assertThrows(IllegalArgumentException.class, () -> new BookingManagementService(bookings, seats, users, jdbc, 0));
        assertThrows(IllegalArgumentException.class, () -> new BookingManagementService(bookings, seats, users, jdbc, -1));
    }

    private Booking prepareBooking(BookingStatus status) {
        Booking booking = mock(Booking.class);
        when(users.existsById(1L)).thenReturn(true);
        when(bookings.findById(5L)).thenReturn(Optional.of(booking));
        when(booking.getUserId()).thenReturn(1L);
        when(booking.getSeatId()).thenReturn(3L);
        when(booking.getStatus()).thenReturn(status);
        return booking;
    }
}
