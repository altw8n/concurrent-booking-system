package com.example.concurrent_booking_system.booking.service;

import com.example.concurrent_booking_system.booking.dto.BookingResponseDto;
import com.example.concurrent_booking_system.exception.BookingConflictException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/** Selects one strategy for the application and retries only completed, failed transactions. */
@Service
public class BookingService {

    private final BookingStrategy strategy;
    private final int maxRetries;

    public BookingService(
            NaiveBookingStrategy naiveStrategy,
            OptimisticBookingStrategy optimisticStrategy,
            @Value("${booking.strategy:naive}") String strategyName,
            @Value("${booking.optimistic.max-retries:0}") int maxRetries
    ) {
        if (maxRetries < 0 || maxRetries > 10) {
            throw new IllegalArgumentException("booking.optimistic.max-retries must be between 0 and 10");
        }
        this.strategy = switch (strategyName) {
            case "naive" -> naiveStrategy;
            case "optimistic" -> optimisticStrategy;
            default -> throw new IllegalArgumentException("booking.strategy must be naive or optimistic");
        };
        this.maxRetries = strategyName.equals("optimistic") ? maxRetries : 0;
    }

    // No transaction here: each call crosses the strategy's Spring proxy and completes its own transaction.
    public BookingResponseDto bookSeat(Long eventId, Long seatId, Long userId) {
        for (int attempt = 0; ; attempt++) {
            try {
                return strategy.bookSeat(eventId, seatId, userId);
            } catch (OptimisticLockingFailureException exception) {
                if (attempt >= maxRetries) {
                    throw new BookingConflictException(seatId, attempt + 1, exception);
                }
            }
        }
    }
}
