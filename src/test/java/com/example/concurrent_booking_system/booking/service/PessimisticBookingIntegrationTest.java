package com.example.concurrent_booking_system.booking.service;

import com.example.concurrent_booking_system.booking.BookingStatus;
import com.example.concurrent_booking_system.booking.dto.BookingResponseDto;
import com.example.concurrent_booking_system.exception.*;
import com.example.concurrent_booking_system.seat.entity.Seat;
import com.example.concurrent_booking_system.seat.repository.SeatRepository;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@Import(PessimisticBookingIntegrationTest.LockTestConfiguration.class)
@SpringBootTest(properties = {
        "booking.strategy=pessimistic",
        "booking.pessimistic.lock-timeout-ms=5000",
        "spring.datasource.hikari.maximum-pool-size=4",
        "spring.datasource.hikari.minimum-idle=0",
        "spring.jpa.show-sql=true"
})
class PessimisticBookingIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("pessimistic_booking_test")
            .withUsername("test_user")
            .withPassword("test_password");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private BookingService bookingService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private DataSource dataSource;
    @Autowired private SeatLockProbe probe;

    private Long eventId;
    private Long seatId;
    private Long firstUserId;
    private Long secondUserId;

    @BeforeEach
    void createTestData() {
        probe.reset();
        eventId = createEvent();
        seatId = createSeat(1);
        firstUserId = createUser();
        secondUserId = createUser();
    }

    @Test
    void booksFreeSeatAndRejectsSequentialBookingWithoutChangingVersion() {
        BookingResponseDto result = bookingService.bookSeat(eventId, seatId, firstUserId);

        assertNotNull(result.id());
        assertEquals(BookingStatus.ACTIVE, result.status());
        assertEquals(firstUserId, result.userId());
        assertNull(result.expiresAt());
        assertSeatBooked(seatId);
        assertThrows(SeatAlreadyBookedException.class,
                () -> bookingService.bookSeat(eventId, seatId, secondUserId));
        assertSeatBooked(seatId);
    }

    @Test
    void secondTransactionWaitsForFirstCommitAndThenSeesBookedSeat() throws Exception {
        probe.holdFirst(seatId, false);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<BookingResponseDto> first = executor.submit(
                    () -> bookingService.bookSeat(eventId, seatId, firstUserId));
            assertTrue(probe.acquired.await(5, TimeUnit.SECONDS), "First strategy must acquire the seat lock");
            Future<BookingResponseDto> second = executor.submit(
                    () -> bookingService.bookSeat(eventId, seatId, secondUserId));

            awaitBlockedBy(probe.blockerPid);
            assertFalse(second.isDone());
            probe.release.countDown();

            assertEquals(firstUserId, first.get(10, TimeUnit.SECONDS).userId());
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> second.get(10, TimeUnit.SECONDS));
            assertInstanceOf(SeatAlreadyBookedException.class, failure.getCause());
            assertSeatBooked(seatId);
        } finally {
            stopWorkers(executor);
        }
    }

    @Test
    void waitingTransactionCanBookAfterFirstTransactionRollsBack() throws Exception {
        probe.holdFirst(seatId, true);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<BookingResponseDto> first = executor.submit(
                    () -> bookingService.bookSeat(eventId, seatId, firstUserId));
            assertTrue(probe.acquired.await(5, TimeUnit.SECONDS));
            Future<BookingResponseDto> second = executor.submit(
                    () -> bookingService.bookSeat(eventId, seatId, secondUserId));

            awaitBlockedBy(probe.blockerPid);
            probe.release.countDown();

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> first.get(10, TimeUnit.SECONDS));
            assertInstanceOf(ForcedRollbackException.class, failure.getCause());
            assertEquals(secondUserId, second.get(10, TimeUnit.SECONDS).userId());
            assertSeatBooked(seatId);
            assertEquals(secondUserId, jdbcTemplate.queryForObject(
                    "SELECT user_id FROM bookings WHERE seat_id = ?", Long.class, seatId));
        } finally {
            stopWorkers(executor);
        }
    }

    @Test
    void differentSeatsCanBeLockedAndBookedConcurrently() throws Exception {
        Long otherSeatId = createSeat(2);
        probe.synchronizeDifferentSeats(seatId, otherSeatId);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<BookingResponseDto> first = executor.submit(
                    () -> bookingService.bookSeat(eventId, seatId, firstUserId));
            Future<BookingResponseDto> second = executor.submit(
                    () -> bookingService.bookSeat(eventId, otherSeatId, secondUserId));
            assertNotNull(first.get(10, TimeUnit.SECONDS).id());
            assertNotNull(second.get(10, TimeUnit.SECONDS).id());
            assertSeatBooked(seatId);
            assertSeatBooked(otherSeatId);
        } finally {
            stopWorkers(executor);
        }
    }

    @Test
    void timesOutWithoutPartialWritesAndRestoresConnectionSettings() throws Exception {
        // This independent transaction holds the row longer than the configured lock_timeout.
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (var statement = holder.prepareStatement("SELECT id FROM seats WHERE id = ? FOR UPDATE")) {
                statement.setLong(1, seatId);
                try (var result = statement.executeQuery()) {
                    assertTrue(result.next());
                }
            }
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<BookingResponseDto> attempt = executor.submit(
                        () -> bookingService.bookSeat(eventId, seatId, firstUserId));
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> attempt.get(15, TimeUnit.SECONDS));
                BookingLockTimeoutException timeout = assertInstanceOf(
                        BookingLockTimeoutException.class, failure.getCause());
                assertEquals("55P03", findSqlException(timeout).getSQLState());
                assertSeatFree();
            } finally {
                holder.rollback();
                stopWorkers(executor);
            }
        }
        bookingService.bookSeat(eventId, seatId, firstUserId);
        assertSeatBooked(seatId);
        assertPoolLockTimeoutWasRestored();
    }

    @Test
    void rollsBackBookingAndReleasesLockWhenSeatUpdateFails() {
        Long previouslyBookedSeatId = createSeat(2);
        bookingService.bookSeat(eventId, previouslyBookedSeatId, firstUserId);
        // Other tests may have booked different seats in this shared database.
        // Reject BOOKED only for this test's seat, without invalidating existing rows.
        jdbcTemplate.execute("""
                ALTER TABLE seats ADD CONSTRAINT chk_test_pessimistic_failure
                CHECK (id <> %d OR status <> 'BOOKED')
                """.formatted(seatId));
        try {
            var failure = assertThrows(DataIntegrityViolationException.class,
                    () -> bookingService.bookSeat(eventId, seatId, firstUserId));
            SQLException sqlException = findSqlException(failure);
            assertEquals("23514", sqlException.getSQLState());
            assertTrue(sqlException.getMessage().contains("chk_test_pessimistic_failure"));
            assertSeatFree();
            assertSeatBooked(previouslyBookedSeatId);
        } finally {
            jdbcTemplate.execute("ALTER TABLE seats DROP CONSTRAINT chk_test_pessimistic_failure");
        }
        bookingService.bookSeat(eventId, seatId, secondUserId);
        assertSeatBooked(seatId);
    }

    @Test
    void appliesSameBusinessValidationWithoutPartialWrites() {
        assertThrows(InvalidBookingRequestException.class,
                () -> bookingService.bookSeat(eventId, seatId, null));
        assertThrows(InvalidBookingRequestException.class,
                () -> bookingService.bookSeat(eventId, seatId, 0L));
        assertThrows(InvalidBookingRequestException.class,
                () -> bookingService.bookSeat(eventId, seatId, -1L));
        assertThrows(EventNotFoundException.class,
                () -> bookingService.bookSeat(-1L, seatId, firstUserId));
        Long missingUserId = createUser();
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", missingUserId);
        assertThrows(UserNotFoundException.class,
                () -> bookingService.bookSeat(eventId, seatId, missingUserId));
        assertThrows(SeatNotFoundException.class,
                () -> bookingService.bookSeat(eventId, -1L, firstUserId));
        Long otherEventId = createEvent();
        assertThrows(SeatNotFoundException.class,
                () -> bookingService.bookSeat(otherEventId, seatId, firstUserId));
        assertSeatFree();
    }

    private void awaitBlockedBy(int blockerPid) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            Boolean waiting = jdbcTemplate.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM pg_stat_activity
                        WHERE datname = current_database()
                          AND wait_event_type = 'Lock'
                          AND ? = ANY(pg_blocking_pids(pid))
                    )
                    """, Boolean.class, blockerPid);
            if (Boolean.TRUE.equals(waiting)) {
                return;
            }
            // Poll actual PostgreSQL lock state; the latch, not a sleep, holds the critical window open.
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        fail("PostgreSQL did not report a transaction waiting for the first strategy's lock");
    }

    private void stopWorkers(ExecutorService executor) throws InterruptedException {
        probe.release.countDown();
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }

    private void assertSeatBooked(Long targetSeatId) {
        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE seat_id = ?", Long.class, targetSeatId));
        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE seat_id = ? AND status = 'ACTIVE'", Long.class, targetSeatId));
        assertEquals("BOOKED", jdbcTemplate.queryForObject(
                "SELECT status FROM seats WHERE id = ?", String.class, targetSeatId));
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT version FROM seats WHERE id = ?", Long.class, targetSeatId));
    }

    private void assertSeatFree() {
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE seat_id = ?", Long.class, seatId));
        assertEquals("FREE", jdbcTemplate.queryForObject(
                "SELECT status FROM seats WHERE id = ?", String.class, seatId));
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT version FROM seats WHERE id = ?", Long.class, seatId));
    }

    private void assertPoolLockTimeoutWasRestored() throws SQLException {
        // Borrow the entire configured pool at once, so every physical connection is checked.
        var connections = new ArrayList<Connection>();
        try {
            for (int i = 0; i < 4; i++) {
                Connection connection = dataSource.getConnection();
                connections.add(connection);
                try (var statement = connection.createStatement();
                     var result = statement.executeQuery("SHOW lock_timeout")) {
                    assertTrue(result.next());
                    assertEquals("0", result.getString(1), "SET LOCAL must not leak into pooled connections");
                }
            }
        } finally {
            for (Connection connection : connections) {
                connection.close();
            }
        }
    }

    private SQLException findSqlException(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                return sqlException;
            }
        }
        throw new AssertionError("Expected a PostgreSQL exception", exception);
    }

    private Long createEvent() {
        return jdbcTemplate.queryForObject("""
                INSERT INTO events (name, description, start_time)
                VALUES ('Pessimistic test event', 'Test data', CURRENT_TIMESTAMP + INTERVAL '1 day') RETURNING id
                """, Long.class);
    }

    private Long createSeat(int number) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO seats (event_id, row_number, seat_number, status, price)
                VALUES (?, 'A', ?, 'FREE', 1500.00) RETURNING id
                """, Long.class, eventId, number);
    }

    private Long createUser() {
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (email, password_hash) VALUES (?, 'test-placeholder') RETURNING id
                """, Long.class, UUID.randomUUID() + "@example.test");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class LockTestConfiguration {
        @Bean
        SeatLockProbe seatLockProbe() {
            return new SeatLockProbe();
        }

        @Bean
        static BeanPostProcessor lockingRepositoryPostProcessor(
                ObjectProvider<SeatLockProbe> probe, ObjectProvider<JdbcTemplate> jdbcTemplate
        ) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof SeatRepository) || !beanName.equals("seatRepository")) {
                        return bean;
                    }
                    ProxyFactory factory = new ProxyFactory(bean);
                    factory.addInterface(SeatRepository.class);
                    factory.addAdvice((MethodInterceptor) invocation -> {
                        Object result = invocation.proceed();
                        if (invocation.getMethod().getName().equals("findByIdForUpdate")
                                && result instanceof Optional<?> seat) {
                            seat.ifPresent(value -> probe.getObject().afterLock((Seat) value, jdbcTemplate.getObject()));
                        }
                        return result;
                    });
                    return factory.getProxy();
                }
            };
        }
    }

    private static class ForcedRollbackException extends RuntimeException { }

    static class SeatLockProbe {
        private volatile Set<Long> targets = Set.of();
        private volatile CyclicBarrier barrier;
        private volatile boolean rollbackFirst;
        private volatile int blockerPid;
        private final AtomicBoolean first = new AtomicBoolean();
        private CountDownLatch acquired = new CountDownLatch(1);
        private CountDownLatch release = new CountDownLatch(1);

        void reset() {
            release.countDown();
            targets = Set.of();
            barrier = null;
            rollbackFirst = false;
            blockerPid = 0;
            first.set(false);
            acquired = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }

        void holdFirst(Long seatId, boolean rollback) {
            rollbackFirst = rollback;
            targets = Set.of(seatId);
        }

        void synchronizeDifferentSeats(Long firstSeatId, Long secondSeatId) {
            barrier = new CyclicBarrier(2);
            targets = Set.of(firstSeatId, secondSeatId);
        }

        void afterLock(Seat seat, JdbcTemplate jdbcTemplate) {
            if (!targets.contains(seat.getId())) {
                return;
            }
            try {
                if (barrier != null) {
                    barrier.await(5, TimeUnit.SECONDS);
                } else if (first.compareAndSet(false, true)) {
                    blockerPid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
                    acquired.countDown();
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Test did not release the seat lock");
                    }
                    if (rollbackFirst) {
                        throw new ForcedRollbackException();
                    }
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Seat lock probe interrupted", exception);
            } catch (BrokenBarrierException | TimeoutException exception) {
                throw new IllegalStateException("Different seats could not be locked concurrently", exception);
            }
        }
    }
}
