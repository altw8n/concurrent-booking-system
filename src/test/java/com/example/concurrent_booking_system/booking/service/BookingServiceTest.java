package com.example.concurrent_booking_system.booking.service;

import com.example.concurrent_booking_system.booking.BookingStatus;
import com.example.concurrent_booking_system.booking.dto.BookingResponseDto;
import com.example.concurrent_booking_system.exception.BookingConflictException;
import com.example.concurrent_booking_system.exception.BookingLockTimeoutException;
import com.example.concurrent_booking_system.exception.SeatAlreadyBookedException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BookingServiceTest {

    private final NaiveBookingStrategy naive = mock(NaiveBookingStrategy.class);
    private final OptimisticBookingStrategy optimistic = mock(OptimisticBookingStrategy.class);
    private final PessimisticBookingStrategy pessimistic = mock(PessimisticBookingStrategy.class);
    private final AtomicBookingStrategy atomic = mock(AtomicBookingStrategy.class);
    private final BookingResponseDto response = new BookingResponseDto(
            5L, 2L, 3L, BookingStatus.ACTIVE, OffsetDateTime.now(), null
    );

    @Test
    void selectsNaiveStrategyWithoutCallingOptimistic() {
        when(naive.bookSeat(1L, 3L, 2L)).thenReturn(response);

        assertSame(response, service("naive", 0).bookSeat(1L, 3L, 2L));
        verifyNoInteractions(optimistic);
    }

    @Test
    void selectsOptimisticStrategyWithoutCallingNaive() {
        when(optimistic.bookSeat(1L, 3L, 2L)).thenReturn(response);

        assertSame(response, service("optimistic", 0).bookSeat(1L, 3L, 2L));
        verifyNoInteractions(naive);
    }

    @Test
    void selectsPessimisticStrategyWithoutCallingOtherStrategies() {
        when(pessimistic.bookSeat(1L, 3L, 2L)).thenReturn(response);

        assertSame(response, service("pessimistic", 0).bookSeat(1L, 3L, 2L));
        verifyNoInteractions(naive, optimistic);
    }

    @Test
    void doesNotRetryPessimisticTimeoutWhenOptimisticRetriesAreEnabled() {
        var failure = new BookingLockTimeoutException(3L, 2000, new RuntimeException("Lock timed out"));
        when(pessimistic.bookSeat(1L, 3L, 2L)).thenThrow(failure);

        assertSame(failure, assertThrows(BookingLockTimeoutException.class,
                () -> service("pessimistic", 2).bookSeat(1L, 3L, 2L)));
        verify(pessimistic).bookSeat(1L, 3L, 2L);
    }

    @Test
    void selectsAtomicStrategyWithoutCallingOtherStrategies() {
        when(atomic.bookSeat(1L, 3L, 2L)).thenReturn(response);

        assertSame(response, service("atomic", 0).bookSeat(1L, 3L, 2L));
        verifyNoInteractions(naive, optimistic, pessimistic);
    }

    @Test
    void doesNotRetryAtomicBusinessRejectionWhenOptimisticRetriesAreEnabled() {
        var failure = new SeatAlreadyBookedException(3L);
        when(atomic.bookSeat(1L, 3L, 2L)).thenThrow(failure);

        assertSame(failure, assertThrows(SeatAlreadyBookedException.class,
                () -> service("atomic", 2).bookSeat(1L, 3L, 2L)));
        verify(atomic).bookSeat(1L, 3L, 2L);
    }

    @Test
    void exposesConflictWithoutRetryByDefault() {
        var failure = new OptimisticLockingFailureException("Version changed");
        when(optimistic.bookSeat(1L, 3L, 2L)).thenThrow(failure);

        var exception = assertThrows(BookingConflictException.class,
                () -> service("optimistic", 0).bookSeat(1L, 3L, 2L));

        assertSame(failure, exception.getCause());
        verify(optimistic).bookSeat(1L, 3L, 2L);
    }

    @Test
    void returnsSuccessfulRetryResult() {
        when(optimistic.bookSeat(1L, 3L, 2L))
                .thenThrow(new OptimisticLockingFailureException("Version changed"))
                .thenReturn(response);

        assertSame(response, service("optimistic", 1).bookSeat(1L, 3L, 2L));
        verify(optimistic, times(2)).bookSeat(1L, 3L, 2L);
    }

    @Test
    void stopsAfterConfiguredNumberOfAdditionalAttempts() {
        when(optimistic.bookSeat(1L, 3L, 2L))
                .thenThrow(new OptimisticLockingFailureException("Version changed"));

        var exception = assertThrows(BookingConflictException.class,
                () -> service("optimistic", 2).bookSeat(1L, 3L, 2L));

        verify(optimistic, times(3)).bookSeat(1L, 3L, 2L);
        assertEquals("Seat 3 was concurrently modified; booking failed after 3 attempt(s)", exception.getMessage());
    }

    @Test
    void doesNotRetryAlreadyBookedSeat() {
        when(optimistic.bookSeat(1L, 3L, 2L)).thenThrow(new SeatAlreadyBookedException(3L));

        assertThrows(SeatAlreadyBookedException.class,
                () -> service("optimistic", 2).bookSeat(1L, 3L, 2L));
        verify(optimistic).bookSeat(1L, 3L, 2L);
    }

    @Test
    void doesNotRetryUnexpectedFailures() {
        var failure = new IllegalStateException("Unexpected failure");
        when(optimistic.bookSeat(1L, 3L, 2L)).thenThrow(failure);

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> service("optimistic", 2).bookSeat(1L, 3L, 2L)));
        verify(optimistic).bookSeat(1L, 3L, 2L);
    }

    @Test
    void ignoresOptimisticRetrySettingForNaiveStrategy() {
        when(naive.bookSeat(1L, 3L, 2L))
                .thenThrow(new OptimisticLockingFailureException("Unexpected version conflict"));

        assertThrows(BookingConflictException.class,
                () -> service("naive", 2).bookSeat(1L, 3L, 2L));
        verify(naive).bookSeat(1L, 3L, 2L);
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> service("unknown", 0));
        assertThrows(IllegalArgumentException.class, () -> service("optimistic", -1));
        assertThrows(IllegalArgumentException.class, () -> service("optimistic", 11));
    }

    private BookingService service(String strategy, int retries) {
        return new BookingService(naive, optimistic, pessimistic, atomic, strategy, retries);
    }
}
