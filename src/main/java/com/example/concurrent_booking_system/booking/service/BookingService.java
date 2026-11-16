package com.example.concurrent_booking_system.booking.service;

import com.example.concurrent_booking_system.booking.dto.BookingResponseDto;
import com.example.concurrent_booking_system.exception.BookingConflictException;
import com.example.concurrent_booking_system.exception.BookingSerializationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.sql.SQLException;

/** Selects one strategy for the application and retries only completed, failed transactions. */
@Service
public class BookingService {

    private final BookingStrategy strategy;
    private final int maxRetries;
    private final boolean serializable;

    public BookingService(
            NaiveBookingStrategy naiveStrategy,
            OptimisticBookingStrategy optimisticStrategy,
            PessimisticBookingStrategy pessimisticStrategy,
            AtomicBookingStrategy atomicStrategy,
            SerializableBookingStrategy serializableStrategy,
            @Value("${booking.strategy:naive}") String strategyName,
            @Value("${booking.optimistic.max-retries:0}") int optimisticMaxRetries,
            @Value("${booking.serializable.max-retries:0}") int serializableMaxRetries
    ) {
        if (optimisticMaxRetries < 0 || optimisticMaxRetries > 10) {
            throw new IllegalArgumentException("booking.optimistic.max-retries must be between 0 and 10");
        }
        if (serializableMaxRetries < 0 || serializableMaxRetries > 10) {
            throw new IllegalArgumentException("booking.serializable.max-retries must be between 0 and 10");
        }
        this.strategy = switch (strategyName) {
            case "naive" -> naiveStrategy;
            case "optimistic" -> optimisticStrategy;
            case "pessimistic" -> pessimisticStrategy;
            case "atomic" -> atomicStrategy;
            case "serializable" -> serializableStrategy;
            default -> throw new IllegalArgumentException(
                    "booking.strategy must be naive, optimistic, pessimistic, atomic or serializable");
        };
        this.serializable = strategyName.equals("serializable");
        this.maxRetries = serializable ? serializableMaxRetries
                : strategyName.equals("optimistic") ? optimisticMaxRetries : 0;
    }

    // No transaction here: each call crosses the strategy's Spring proxy and completes its own transaction.
    public BookingResponseDto bookSeat(Long eventId, Long seatId, Long userId) {
        for (int attempt = 0; ; attempt++) {
            try {
                return strategy.bookSeat(eventId, seatId, userId);
            } catch (RuntimeException exception) {
                // Translation differs for failures at SQL execution and at commit; SQLSTATE is stable.
                if (serializable) {
                    if (!isSerializationFailure(exception)) {
                        throw exception;
                    }
                    if (attempt >= maxRetries) {
                        throw new BookingSerializationException(seatId, attempt + 1, exception);
                    }
                } else if (exception instanceof OptimisticLockingFailureException) {
                    if (attempt >= maxRetries) {
                        throw new BookingConflictException(seatId, attempt + 1, exception);
                    }
                } else {
                    throw exception;
                }
            }
        }
    }

    private static boolean isSerializationFailure(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException && "40001".equals(sqlException.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
