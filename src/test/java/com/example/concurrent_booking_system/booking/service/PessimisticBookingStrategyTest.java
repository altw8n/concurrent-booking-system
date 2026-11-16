package com.example.concurrent_booking_system.booking.service;

import com.example.concurrent_booking_system.booking.repository.BookingRepository;
import com.example.concurrent_booking_system.event.repository.EventRepository;
import com.example.concurrent_booking_system.exception.BookingLockTimeoutException;
import com.example.concurrent_booking_system.seat.repository.SeatRepository;
import com.example.concurrent_booking_system.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class PessimisticBookingStrategyTest {
    private final EventRepository events = mock(EventRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final SeatRepository seats = mock(SeatRepository.class);
    private final BookingRepository bookings = mock(BookingRepository.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

    @BeforeEach
    void prepareValidRequest() {
        when(events.existsById(1L)).thenReturn(true);
        when(users.existsById(2L)).thenReturn(true);
    }

    @Test
    void translatesPostgresLockNotAvailableIntoBookingTimeout() {
        var failure = new CannotAcquireLockException("Lock failed", new SQLException("Lock timeout", "55P03"));
        when(seats.findByIdForUpdate(3L)).thenThrow(failure);

        var exception = assertThrows(BookingLockTimeoutException.class,
                () -> strategy(2000).bookSeat(1L, 3L, 2L));

        assertSame(failure, exception.getCause());
        verifyNoInteractions(bookings);
    }

    @Test
    void doesNotMisclassifyDeadlocksOrOtherLockFailuresAsTimeouts() {
        for (String sqlState : new String[]{"40P01", "57014", null}) {
            var failure = new CannotAcquireLockException("Lock failed", new SQLException("Other failure", sqlState));
            doThrow(failure).when(seats).findByIdForUpdate(3L);

            assertSame(failure, assertThrows(CannotAcquireLockException.class,
                    () -> strategy(2000).bookSeat(1L, 3L, 2L)));
        }
        verifyNoInteractions(bookings);
    }

    @Test
    void rejectsNonPositiveLockTimeout() {
        assertThrows(IllegalArgumentException.class, () -> strategy(0));
        assertThrows(IllegalArgumentException.class, () -> strategy(-1));
    }

    private PessimisticBookingStrategy strategy(int timeoutMs) {
        return new PessimisticBookingStrategy(events, users, seats, bookings, jdbcTemplate, timeoutMs);
    }
}
