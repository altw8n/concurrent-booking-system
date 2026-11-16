package com.example.concurrent_booking_system.booking.service;

import com.example.concurrent_booking_system.booking.dto.BookingResponseDto;
import com.example.concurrent_booking_system.booking.entity.Booking;
import com.example.concurrent_booking_system.booking.repository.BookingRepository;
import com.example.concurrent_booking_system.event.repository.EventRepository;
import com.example.concurrent_booking_system.exception.BookingLockTimeoutException;
import com.example.concurrent_booking_system.exception.EventNotFoundException;
import com.example.concurrent_booking_system.exception.InvalidBookingRequestException;
import com.example.concurrent_booking_system.exception.SeatAlreadyBookedException;
import com.example.concurrent_booking_system.exception.SeatNotFoundException;
import com.example.concurrent_booking_system.exception.UserNotFoundException;
import com.example.concurrent_booking_system.seat.SeatStatus;
import com.example.concurrent_booking_system.seat.entity.Seat;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
public class PessimisticBookingStrategy implements BookingStrategy {

    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final SeatRepository seatRepository;
    private final BookingRepository bookingRepository;
    private final JdbcTemplate jdbcTemplate;
    private final int lockTimeoutMs;

    public PessimisticBookingStrategy(
            EventRepository eventRepository,
            UserRepository userRepository,
            SeatRepository seatRepository,
            BookingRepository bookingRepository,
            JdbcTemplate jdbcTemplate,
            @Value("${booking.pessimistic.lock-timeout-ms:2000}") int lockTimeoutMs
    ) {
        if (lockTimeoutMs <= 0) {
            throw new IllegalArgumentException("booking.pessimistic.lock-timeout-ms must be positive");
        }
        this.eventRepository = eventRepository;
        this.userRepository = userRepository;
        this.seatRepository = seatRepository;
        this.bookingRepository = bookingRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.lockTimeoutMs = lockTimeoutMs;
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
        // JpaTransactionManager makes JdbcTemplate use the same transactional connection as Hibernate.
        // true means SET LOCAL: the setting is restored on commit or rollback, before pool reuse.
        jdbcTemplate.queryForObject("SELECT set_config('lock_timeout', ?, true)",
                String.class, lockTimeoutMs + "ms");

        Seat seat;
        try {
            seat = seatRepository.findByIdForUpdate(seatId)
                    .filter(foundSeat -> eventId.equals(foundSeat.getEventId()))
                    .orElseThrow(() -> new SeatNotFoundException(seatId, eventId));
        } catch (PessimisticLockingFailureException exception) {
            if (isLockTimeout(exception)) {
                throw new BookingLockTimeoutException(seatId, lockTimeoutMs, exception);
            }
            throw exception;
        }
        if (seat.getStatus() != SeatStatus.FREE) {
            throw new SeatAlreadyBookedException(seatId);
        }
        Booking booking = Booking.createActive(userId, seatId, OffsetDateTime.now(ZoneOffset.UTC));
        Booking savedBooking = bookingRepository.save(booking);
        seat.markBooked();
        return new BookingResponseDto(
                savedBooking.getId(), savedBooking.getUserId(), savedBooking.getSeatId(),
                savedBooking.getStatus(), savedBooking.getCreatedAt(), savedBooking.getExpiresAt()
        );
    }

    private boolean isLockTimeout(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException && "55P03".equals(sqlException.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
