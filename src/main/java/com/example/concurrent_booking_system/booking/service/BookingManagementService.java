package com.example.concurrent_booking_system.booking.service;

import com.example.concurrent_booking_system.booking.BookingStatus;
import com.example.concurrent_booking_system.booking.dto.BookingResponseDto;
import com.example.concurrent_booking_system.booking.entity.Booking;
import com.example.concurrent_booking_system.booking.repository.BookingRepository;
import com.example.concurrent_booking_system.exception.BookingAccessDeniedException;
import com.example.concurrent_booking_system.exception.BookingCancellationTimeoutException;
import com.example.concurrent_booking_system.exception.BookingNotFoundException;
import com.example.concurrent_booking_system.exception.InvalidBookingRequestException;
import com.example.concurrent_booking_system.exception.UserNotFoundException;
import com.example.concurrent_booking_system.seat.repository.SeatRepository;
import com.example.concurrent_booking_system.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.List;

@Service
public class BookingManagementService {

    private final BookingRepository bookingRepository;
    private final SeatRepository seatRepository;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;
    private final int lockTimeoutMs;

    public BookingManagementService(BookingRepository bookingRepository, SeatRepository seatRepository,
                                    UserRepository userRepository, JdbcTemplate jdbcTemplate,
                                    @Value("${booking.cancellation.lock-timeout-ms:2000}") int lockTimeoutMs) {
        if (lockTimeoutMs <= 0) {
            throw new IllegalArgumentException("booking.cancellation.lock-timeout-ms must be positive");
        }
        this.bookingRepository = bookingRepository;
        this.seatRepository = seatRepository;
        this.userRepository = userRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.lockTimeoutMs = lockTimeoutMs;
    }

    @Transactional(readOnly = true)
    public List<BookingResponseDto> getUserBookings(Long userId) {
        requireUser(userId);
        return bookingRepository.findAllByUserIdOrderByCreatedAtDescIdDesc(userId).stream()
                .map(booking -> new BookingResponseDto(
                        booking.getId(), booking.getUserId(), booking.getSeatId(), booking.getStatus(),
                        booking.getCreatedAt(), booking.getExpiresAt()))
                .toList();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void cancelBooking(Long bookingId, Long userId) {
        if (bookingId == null || bookingId <= 0) {
            throw new InvalidBookingRequestException("bookingId must be a positive number");
        }
        requireUser(userId);
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new BookingNotFoundException(bookingId));
        if (!userId.equals(booking.getUserId())) {
            throw new BookingAccessDeniedException(bookingId);
        }
        if (booking.getStatus() == BookingStatus.CANCELLED) {
            return;
        }

        // Local setting and all repository calls share the transaction's JDBC connection.
        jdbcTemplate.queryForObject("SELECT set_config('lock_timeout', ?, true)",
                String.class, lockTimeoutMs + "ms");
        try {
            // Consistent lock order: seat first, then booking. Do not trust the initial status after waiting.
            seatRepository.findByIdForUpdate(booking.getSeatId())
                    .orElseThrow(() -> new IllegalStateException("Booking references a missing seat"));
            if (bookingRepository.cancelIfActive(bookingId) == 1) {
                // Also advances the version so an optimistic writer cannot reuse a pre-cancellation version.
                // Existing duplicate ACTIVE bookings from naive must not leave another owner's seat FREE.
                seatRepository.releaseIfNoActiveBookings(booking.getSeatId());
            }
        } catch (PessimisticLockingFailureException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && "55P03".equals(sql.getSQLState())) {
                    throw new BookingCancellationTimeoutException(bookingId, lockTimeoutMs, exception);
                }
            }
            throw exception;
        }
    }

    private void requireUser(Long userId) {
        if (userId == null || userId <= 0) {
            throw new InvalidBookingRequestException("userId must be a positive number");
        }
        if (!userRepository.existsById(userId)) {
            throw new UserNotFoundException(userId);
        }
    }
}
