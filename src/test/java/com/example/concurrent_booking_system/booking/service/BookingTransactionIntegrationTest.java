package com.example.concurrent_booking_system.booking.service;

import com.example.concurrent_booking_system.booking.dto.BookingResponseDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
@SpringBootTest(properties = {"spring.jpa.show-sql=true", "booking.strategy=naive"})
class BookingTransactionIntegrationTest {

    private static final String FAILURE_CONSTRAINT = "chk_test_reject_booked_seat";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("booking_transaction_test")
            .withUsername("test_user")
            .withPassword("test_password");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private BookingService bookingService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void rollsBackBookingWhenSeatUpdateFails() {
        Long eventId = jdbcTemplate.queryForObject(
                """
                INSERT INTO events (name, description, start_time)
                VALUES ('Rollback test event', 'Test data', CURRENT_TIMESTAMP + INTERVAL '1 day')
                RETURNING id
                """,
                Long.class
        );
        Long seatId = jdbcTemplate.queryForObject(
                """
                INSERT INTO seats (event_id, row_number, seat_number, status, price)
                VALUES (?, 'A', 1, 'FREE', 1500.00)
                RETURNING id
                """,
                Long.class,
                eventId
        );
        Long userId = jdbcTemplate.queryForObject(
                """
                INSERT INTO users (email, password_hash)
                VALUES (?, 'test-placeholder')
                RETURNING id
                """,
                Long.class,
                UUID.randomUUID() + "@example.test"
        );

        jdbcTemplate.execute("""
                ALTER TABLE seats
                ADD CONSTRAINT %s CHECK (status <> 'BOOKED')
                """.formatted(FAILURE_CONSTRAINT));

        try {
            // No test transaction: the service proxy must perform commit or rollback itself.
            // IDENTITY inserts Booking before the Seat update fails during flush at commit.
            DataIntegrityViolationException exception = assertThrows(
                    DataIntegrityViolationException.class,
                    () -> bookingService.bookSeat(eventId, seatId, userId)
            );
            SQLException sqlException = assertInstanceOf(
                    SQLException.class,
                    exception.getMostSpecificCause()
            );
            assertEquals("23514", sqlException.getSQLState());
            assertTrue(sqlException.getMessage().contains(FAILURE_CONSTRAINT));

            assertEquals(0L, countBookingsForSeat(seatId));
            assertEquals("FREE", getSeatStatus(seatId));
        } finally {
            jdbcTemplate.execute("ALTER TABLE seats DROP CONSTRAINT " + FAILURE_CONSTRAINT);
        }

        BookingResponseDto booking = bookingService.bookSeat(eventId, seatId, userId);

        assertNotNull(booking.id());
        assertEquals(1L, countBookingsForSeat(seatId));
        assertEquals("BOOKED", getSeatStatus(seatId));
    }

    private long countBookingsForSeat(Long seatId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE seat_id = ?",
                Long.class,
                seatId
        );
        assertNotNull(count);
        return count;
    }

    private String getSeatStatus(Long seatId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM seats WHERE id = ?",
                String.class,
                seatId
        );
    }
}
