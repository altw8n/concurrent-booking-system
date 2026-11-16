package com.example.concurrent_booking_system.booking.service;

import com.example.concurrent_booking_system.booking.BookingStatus;
import com.example.concurrent_booking_system.booking.dto.BookingResponseDto;
import com.example.concurrent_booking_system.exception.*;
import com.example.concurrent_booking_system.seat.SeatStatus;
import com.example.concurrent_booking_system.seat.entity.Seat;
import com.example.concurrent_booking_system.seat.repository.SeatRepository;
import jakarta.persistence.EntityManager;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@Import(AtomicBookingIntegrationTest.UpdateTestConfiguration.class)
@SpringBootTest(properties = {
        "booking.strategy=atomic",
        "spring.datasource.hikari.maximum-pool-size=6",
        "spring.datasource.hikari.minimum-idle=0",
        "spring.jpa.show-sql=true"
})
class AtomicBookingIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("atomic_booking_test")
            .withUsername("test_user")
            .withPassword("test_password");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private BookingService bookingService;
    @Autowired private SeatRepository seatRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private UpdateProbe probe;

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
    void booksFreeSeatAndRejectsSequentialAttempt() {
        BookingResponseDto booking = bookingService.bookSeat(eventId, seatId, firstUserId);

        assertNotNull(booking.id());
        assertEquals(firstUserId, booking.userId());
        assertEquals(seatId, booking.seatId());
        assertEquals(BookingStatus.ACTIVE, booking.status());
        assertNull(booking.expiresAt());
        assertSeatBooked(seatId);
        assertThrows(SeatAlreadyBookedException.class,
                () -> bookingService.bookSeat(eventId, seatId, secondUserId));
        assertSeatBooked(seatId);
    }

    @Test
    void onlyOneOfFourConcurrentAttemptsCreatesBooking() throws Exception {
        List<Long> users = List.of(firstUserId, secondUserId, createUser(), createUser());
        probe.synchronizeBeforeUpdate(seatId, users.size());
        ExecutorService executor = Executors.newFixedThreadPool(users.size());
        try {
            List<Future<Attempt>> futures = new ArrayList<>();
            for (Long userId : users) {
                futures.add(executor.submit(() -> {
                    try {
                        return new Attempt(bookingService.bookSeat(eventId, seatId, userId), null);
                    } catch (SeatAlreadyBookedException exception) {
                        return new Attempt(null, exception);
                    }
                }));
            }
            List<Attempt> results = new ArrayList<>();
            for (Future<Attempt> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            assertEquals(1L, results.stream().filter(result -> result.booking() != null).count());
            assertEquals(3L, results.stream().filter(result -> result.rejection() != null).count());
            BookingResponseDto winner = results.stream().filter(result -> result.booking() != null)
                    .findFirst().orElseThrow().booking();
            assertEquals(winner.userId(), jdbcTemplate.queryForObject(
                    "SELECT user_id FROM bookings WHERE seat_id = ?", Long.class, seatId));
            assertSeatBooked(seatId);
        } finally {
            stopWorkers(executor);
        }
    }

    @Test
    void differentSeatsCanBeUpdatedBeforeEitherTransactionCommits() throws Exception {
        Long otherSeatId = createSeat(2);
        probe.synchronizeAfterUpdates(seatId, otherSeatId);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<BookingResponseDto> first = executor.submit(
                    () -> bookingService.bookSeat(eventId, seatId, firstUserId));
            Future<BookingResponseDto> second = executor.submit(
                    () -> bookingService.bookSeat(eventId, otherSeatId, secondUserId));
            assertNotNull(first.get(15, TimeUnit.SECONDS).id());
            assertNotNull(second.get(15, TimeUnit.SECONDS).id());
            assertSeatBooked(seatId);
            assertSeatBooked(otherSeatId);
        } finally {
            stopWorkers(executor);
        }
    }

    @Test
    void waitingUpdateRechecksFreeConditionAfterFirstCommit() throws Exception {
        probe.holdFirstUpdate(seatId, false);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<BookingResponseDto> first = executor.submit(
                    () -> bookingService.bookSeat(eventId, seatId, firstUserId));
            assertTrue(probe.updated.await(5, TimeUnit.SECONDS));
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
    void waitingUpdateCanClaimSeatAfterFirstRollback() throws Exception {
        probe.holdFirstUpdate(seatId, true);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<BookingResponseDto> first = executor.submit(
                    () -> bookingService.bookSeat(eventId, seatId, firstUserId));
            assertTrue(probe.updated.await(5, TimeUnit.SECONDS));
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
    void rollsBackSeatUpdateWhenBookingInsertFails() {
        Long previouslyBookedSeatId = createSeat(2);
        bookingService.bookSeat(eventId, previouslyBookedSeatId, secondUserId);
        // Only reject this test's INSERT; previously created bookings remain valid.
        jdbcTemplate.execute("""
                ALTER TABLE bookings ADD CONSTRAINT chk_test_atomic_insert_failure
                CHECK (seat_id <> %d)
                """.formatted(seatId));
        try {
            var failure = assertThrows(DataIntegrityViolationException.class,
                    () -> bookingService.bookSeat(eventId, seatId, firstUserId));
            SQLException sqlException = assertInstanceOf(SQLException.class, failure.getMostSpecificCause());
            assertEquals("23514", sqlException.getSQLState());
            assertTrue(sqlException.getMessage().contains("chk_test_atomic_insert_failure"));
            assertSeatFree();
            assertSeatBooked(previouslyBookedSeatId);
        } finally {
            jdbcTemplate.execute("ALTER TABLE bookings DROP CONSTRAINT chk_test_atomic_insert_failure");
        }
        bookingService.bookSeat(eventId, seatId, firstUserId);
        assertSeatBooked(seatId);
    }

    @Test
    void bulkUpdateClearsStaleManagedSeat() {
        new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
            Seat original = seatRepository.findById(seatId).orElseThrow();
            assertTrue(entityManager.contains(original));
            assertEquals(SeatStatus.FREE, original.getStatus());

            assertEquals(1, seatRepository.bookIfFree(eventId, seatId));

            assertFalse(entityManager.contains(original), "Bulk SQL must detach the stale entity");
            assertEquals(SeatStatus.FREE, original.getStatus(), "Detaching does not rewrite the old Java object");
            Seat reloaded = seatRepository.findById(seatId).orElseThrow();
            assertEquals(SeatStatus.BOOKED, reloaded.getStatus());
            entityManager.flush();
            assertEquals("BOOKED", jdbcTemplate.queryForObject(
                    "SELECT status FROM seats WHERE id = ?", String.class, seatId));
            // This repository-only test deliberately omits Booking; do not commit that intermediate state.
            transaction.setRollbackOnly();
        });
        assertSeatFree();
    }

    @Test
    void distinguishesMissingSeatFromBookedSeatAndRejectsInvalidRequests() {
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

        bookingService.bookSeat(eventId, seatId, firstUserId);
        assertThrows(SeatNotFoundException.class,
                () -> bookingService.bookSeat(otherEventId, seatId, secondUserId));
        assertThrows(SeatAlreadyBookedException.class,
                () -> bookingService.bookSeat(eventId, seatId, secondUserId));
        assertSeatBooked(seatId);
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

    private void awaitBlockedBy(int blockerPid) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
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
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        fail("PostgreSQL did not report a transaction waiting for the first UPDATE");
    }

    private void stopWorkers(ExecutorService executor) throws InterruptedException {
        probe.release.countDown();
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }

    private Long createEvent() {
        return jdbcTemplate.queryForObject("""
                INSERT INTO events (name, description, start_time)
                VALUES ('Atomic test event', 'Test data', CURRENT_TIMESTAMP + INTERVAL '1 day') RETURNING id
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

    private record Attempt(BookingResponseDto booking, SeatAlreadyBookedException rejection) { }
    private static class ForcedRollbackException extends RuntimeException { }

    @TestConfiguration(proxyBeanMethods = false)
    static class UpdateTestConfiguration {
        @Bean
        UpdateProbe updateProbe() {
            return new UpdateProbe();
        }

        @Bean
        static BeanPostProcessor atomicRepositoryPostProcessor(
                ObjectProvider<UpdateProbe> probe, ObjectProvider<JdbcTemplate> jdbcTemplate
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
                        if (!invocation.getMethod().getName().equals("bookIfFree")) {
                            return invocation.proceed();
                        }
                        Long targetSeatId = (Long) invocation.getArguments()[1];
                        probe.getObject().beforeUpdate(targetSeatId);
                        Object result = invocation.proceed();
                        probe.getObject().afterUpdate(targetSeatId, (Integer) result, jdbcTemplate.getObject());
                        return result;
                    });
                    return factory.getProxy();
                }
            };
        }
    }

    static class UpdateProbe {
        private volatile Set<Long> targets = Set.of();
        private volatile CyclicBarrier beforeBarrier;
        private volatile CyclicBarrier afterBarrier;
        private volatile boolean rollbackFirst;
        private volatile int blockerPid;
        private final AtomicBoolean first = new AtomicBoolean();
        private CountDownLatch updated = new CountDownLatch(1);
        private CountDownLatch release = new CountDownLatch(1);

        void reset() {
            release.countDown();
            targets = Set.of();
            beforeBarrier = null;
            afterBarrier = null;
            rollbackFirst = false;
            blockerPid = 0;
            first.set(false);
            updated = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }

        void synchronizeBeforeUpdate(Long targetSeatId, int participants) {
            beforeBarrier = new CyclicBarrier(participants);
            targets = Set.of(targetSeatId);
        }

        void synchronizeAfterUpdates(Long firstSeatId, Long secondSeatId) {
            afterBarrier = new CyclicBarrier(2);
            targets = Set.of(firstSeatId, secondSeatId);
        }

        void holdFirstUpdate(Long targetSeatId, boolean rollback) {
            rollbackFirst = rollback;
            targets = Set.of(targetSeatId);
        }

        void beforeUpdate(Long targetSeatId) {
            if (targets.contains(targetSeatId) && beforeBarrier != null) {
                await(beforeBarrier);
            }
        }

        void afterUpdate(Long targetSeatId, int rows, JdbcTemplate jdbcTemplate) {
            if (!targets.contains(targetSeatId) || rows == 0 || beforeBarrier != null) {
                return;
            }
            if (afterBarrier != null) {
                await(afterBarrier);
            } else if (first.compareAndSet(false, true)) {
                blockerPid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
                updated.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Test did not release the updated seat");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Atomic update probe interrupted", exception);
                }
                if (rollbackFirst) {
                    throw new ForcedRollbackException();
                }
            }
        }

        private void await(CyclicBarrier barrier) {
            try {
                barrier.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Atomic update barrier interrupted", exception);
            } catch (BrokenBarrierException | TimeoutException exception) {
                throw new IllegalStateException("Atomic updates did not reach the barrier", exception);
            }
        }
    }
}
