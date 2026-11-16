package com.example.concurrent_booking_system.booking.service;

import com.example.concurrent_booking_system.booking.BookingStatus;
import com.example.concurrent_booking_system.booking.dto.BookingResponseDto;
import com.example.concurrent_booking_system.exception.BookingSerializationException;
import com.example.concurrent_booking_system.exception.EventNotFoundException;
import com.example.concurrent_booking_system.exception.InvalidBookingRequestException;
import com.example.concurrent_booking_system.exception.SeatAlreadyBookedException;
import com.example.concurrent_booking_system.exception.SeatNotFoundException;
import com.example.concurrent_booking_system.exception.UserNotFoundException;
import com.example.concurrent_booking_system.seat.entity.Seat;
import com.example.concurrent_booking_system.seat.SeatStatus;
import com.example.concurrent_booking_system.seat.repository.SeatRepository;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
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

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@Import(SerializableBookingIntegrationTest.SynchronizationConfiguration.class)
@SpringBootTest(properties = {
        "booking.strategy=serializable",
        "booking.serializable.max-retries=0",
        "spring.datasource.hikari.maximum-pool-size=4",
        "spring.datasource.hikari.minimum-idle=0",
        "spring.jpa.show-sql=true"
})
class SerializableBookingIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("serializable_booking_test")
            .withUsername("test_user")
            .withPassword("test_password");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private BookingService bookingService;
    @Autowired private NaiveBookingStrategy naiveStrategy;
    @Autowired private OptimisticBookingStrategy optimisticStrategy;
    @Autowired private PessimisticBookingStrategy pessimisticStrategy;
    @Autowired private AtomicBookingStrategy atomicStrategy;
    @Autowired private SerializableBookingStrategy serializableStrategy;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SeatReadSynchronizer synchronizer;

    private Long eventId;
    private Long seatId;
    private Long firstUserId;
    private Long secondUserId;

    @BeforeEach
    void createTestData() {
        eventId = createEvent();
        seatId = jdbcTemplate.queryForObject("""
                INSERT INTO seats (event_id, row_number, seat_number, status, price)
                VALUES (?, 'A', 1, 'FREE', 1500.00) RETURNING id
                """, Long.class, eventId);
        firstUserId = createUser();
        secondUserId = createUser();
    }

    @Test
    void booksFreeSeatWithoutVersionBasedLocking() {
        jdbcTemplate.update("UPDATE seats SET version = 41 WHERE id = ?", seatId);

        BookingResponseDto result = bookingService.bookSeat(eventId, seatId, firstUserId);

        assertNotNull(result.id());
        assertEquals(firstUserId, result.userId());
        assertEquals(BookingStatus.ACTIVE, result.status());
        assertNull(result.expiresAt());
        assertSeatAndBookings(41L);
        assertThrows(SeatAlreadyBookedException.class,
                () -> bookingService.bookSeat(eventId, seatId, secondUserId));
        assertSeatAndBookings(41L);
    }

    @Test
    void rejectsStaleTransactionWhenBothReadFreeSeat() throws Exception {
        List<Attempt> results = runRace(bookingService);

        assertSingleWinner(results);
        RuntimeException failure = results.stream().filter(r -> r.failure() != null)
                .findFirst().orElseThrow().failure();
        assertInstanceOf(BookingSerializationException.class, failure);
        assertTrue(hasSqlState(failure, "40001"));
        assertEquals(2, synchronizer.reads.size());
        assertTrue(synchronizer.reads.stream().allMatch(read -> read.status() == SeatStatus.FREE));
        assertSerializableTransactions();
        assertSeatAndBookings(0L);
    }

    @Test
    void retryUsesNewTransactionAndSeesCommittedBookedSeat() throws Exception {
        // The strategy arguments are real Spring transaction proxies, not manually constructed strategies.
        BookingService retryingService = new BookingService(
                naiveStrategy, optimisticStrategy, pessimisticStrategy, atomicStrategy,
                serializableStrategy, "serializable", 0, 1);
        List<Attempt> results = runRace(retryingService);

        assertSingleWinner(results);
        RuntimeException failure = results.stream().filter(r -> r.failure() != null)
                .findFirst().orElseThrow().failure();
        assertInstanceOf(SeatAlreadyBookedException.class, failure);
        List<ReadObservation> reads = List.copyOf(synchronizer.reads);
        assertEquals(3, reads.size(), "Two initial reads and exactly one retry");
        assertEquals(3L, reads.stream().map(ReadObservation::transactionId).distinct().count(),
                "Every attempt must use a fresh physical PostgreSQL transaction");
        assertEquals(SeatStatus.BOOKED, reads.get(2).status());
        assertSerializableTransactions();
        assertSeatAndBookings(0L);
    }

    @Test
    void rollsBackInsertWhenSeatUpdateFails() {
        Long previouslyBookedSeatId = jdbcTemplate.queryForObject("""
                INSERT INTO seats (event_id, row_number, seat_number, status, price)
                VALUES (?, 'A', 2, 'FREE', 1500.00) RETURNING id
                """, Long.class, eventId);
        bookingService.bookSeat(eventId, previouslyBookedSeatId, secondUserId);
        // Scope failure injection to this seat so test order cannot invalidate the constraint.
        jdbcTemplate.execute("""
                ALTER TABLE seats ADD CONSTRAINT chk_test_serializable_failure
                CHECK (id <> %d OR status <> 'BOOKED')
                """.formatted(seatId));
        try {
            var exception = assertThrows(DataIntegrityViolationException.class,
                    () -> bookingService.bookSeat(eventId, seatId, firstUserId));
            SQLException cause = assertInstanceOf(SQLException.class, exception.getMostSpecificCause());
            assertEquals("23514", cause.getSQLState());
            assertTrue(cause.getMessage().contains("chk_test_serializable_failure"));
            assertEquals(0L, countBookings());
            assertEquals("FREE", seatStatus());
            assertEquals(0L, seatVersion());
            assertEquals("BOOKED", jdbcTemplate.queryForObject(
                    "SELECT status FROM seats WHERE id = ?", String.class, previouslyBookedSeatId));
            assertEquals(1L, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM bookings WHERE seat_id = ?", Long.class, previouslyBookedSeatId));
        } finally {
            jdbcTemplate.execute("ALTER TABLE seats DROP CONSTRAINT chk_test_serializable_failure");
        }
        bookingService.bookSeat(eventId, seatId, firstUserId);
        assertSeatAndBookings(0L);
    }

    @Test
    void appliesSameValidationAndDoesNotWriteOnInvalidRequests() {
        assertThrows(InvalidBookingRequestException.class,
                () -> bookingService.bookSeat(eventId, seatId, null));
        assertThrows(InvalidBookingRequestException.class,
                () -> bookingService.bookSeat(eventId, seatId, 0L));
        assertThrows(InvalidBookingRequestException.class,
                () -> bookingService.bookSeat(eventId, seatId, -1L));
        assertThrows(EventNotFoundException.class,
                () -> bookingService.bookSeat(-1L, seatId, firstUserId));
        // A negative ID is invalid input. Use a valid, deliberately absent ID to test lookup failure.
        Long missingUserId = createUser();
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", missingUserId);
        assertThrows(UserNotFoundException.class,
                () -> bookingService.bookSeat(eventId, seatId, missingUserId));
        assertThrows(SeatNotFoundException.class,
                () -> bookingService.bookSeat(eventId, -1L, firstUserId));
        Long otherEventId = createEvent();
        assertThrows(SeatNotFoundException.class,
                () -> bookingService.bookSeat(otherEventId, seatId, firstUserId));
        assertEquals(0L, countBookings());
        assertEquals("FREE", seatStatus());
        assertEquals(0L, seatVersion());
    }

    private List<Attempt> runRace(BookingService service) throws Exception {
        synchronizer.enableFor(seatId);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Attempt> first = executor.submit(() -> attempt(service, firstUserId));
            Future<Attempt> second = executor.submit(() -> attempt(service, secondUserId));
            return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private Attempt attempt(BookingService service, Long userId) {
        try {
            BookingResponseDto result = service.bookSeat(eventId, seatId, userId);
            // The service returned through the transaction proxy: the winner has committed.
            synchronizer.firstCommit.countDown();
            return new Attempt(result, null);
        } catch (BookingSerializationException | SeatAlreadyBookedException exception) {
            return new Attempt(null, exception);
        }
    }

    private void assertSingleWinner(List<Attempt> results) {
        assertEquals(1L, results.stream().filter(r -> r.booking() != null).count());
        assertEquals(1L, results.stream().filter(r -> r.failure() != null).count());
        BookingResponseDto winner = results.stream().filter(r -> r.booking() != null)
                .findFirst().orElseThrow().booking();
        assertEquals(winner.userId(), jdbcTemplate.queryForObject(
                "SELECT user_id FROM bookings WHERE seat_id = ?", Long.class, seatId));
    }

    private void assertSeatAndBookings(long expectedVersion) {
        assertEquals(1L, countBookings(), "The losing INSERT must not survive rollback");
        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE seat_id = ? AND status = 'ACTIVE'", Long.class, seatId));
        assertEquals("BOOKED", seatStatus());
        assertEquals(expectedVersion, seatVersion());
    }

    private long countBookings() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bookings WHERE seat_id = ?", Long.class, seatId);
    }

    private long seatVersion() {
        return jdbcTemplate.queryForObject("SELECT version FROM seats WHERE id = ?", Long.class, seatId);
    }

    private String seatStatus() {
        return jdbcTemplate.queryForObject("SELECT status FROM seats WHERE id = ?", String.class, seatId);
    }

    private Long createEvent() {
        return jdbcTemplate.queryForObject("""
                INSERT INTO events (name, description, start_time)
                VALUES ('Serializable test event', 'Test data', CURRENT_TIMESTAMP + INTERVAL '1 day') RETURNING id
                """, Long.class);
    }

    private Long createUser() {
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (email, password_hash) VALUES (?, 'test-placeholder') RETURNING id
                """, Long.class, UUID.randomUUID() + "@example.test");
    }

    private record Attempt(BookingResponseDto booking, RuntimeException failure) { }
    private record ReadObservation(String transactionId, String isolation, SeatStatus status) { }

    private void assertSerializableTransactions() {
        assertTrue(synchronizer.reads.stream().allMatch(read -> read.isolation().equals("serializable")));
        assertEquals(synchronizer.reads.size(),
                synchronizer.reads.stream().map(ReadObservation::transactionId).distinct().count());
    }

    private boolean hasSqlState(Throwable exception, String state) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && state.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SynchronizationConfiguration {
        @Bean
        SeatReadSynchronizer seatReadSynchronizer() {
            return new SeatReadSynchronizer();
        }

        @Bean
        static BeanPostProcessor seatRepositoryPostProcessor(
                ObjectProvider<SeatReadSynchronizer> synchronizer, ObjectProvider<JdbcTemplate> jdbcTemplate
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
                        if (invocation.getMethod().getName().equals("findById") && result instanceof Optional<?> seat) {
                            seat.ifPresent(value -> synchronizer.getObject().afterRead((Seat) value, jdbcTemplate.getObject()));
                        }
                        return result;
                    });
                    return factory.getProxy();
                }
            };
        }
    }

    static class SeatReadSynchronizer {
        private volatile Set<Long> targetSeatIds = Set.of();
        private volatile CyclicBarrier barrier;
        private volatile CountDownLatch firstCommit = new CountDownLatch(1);
        private final AtomicInteger readCount = new AtomicInteger();
        private final ConcurrentLinkedQueue<ReadObservation> reads = new ConcurrentLinkedQueue<>();

        void enableFor(Long... seatIds) {
            reads.clear();
            readCount.set(0);
            barrier = new CyclicBarrier(2);
            firstCommit = new CountDownLatch(1);
            targetSeatIds = Set.of(seatIds);
        }

        void afterRead(Seat seat, JdbcTemplate jdbcTemplate) {
            if (!targetSeatIds.contains(seat.getId())) {
                return;
            }
            String transactionId = jdbcTemplate.queryForObject("SELECT pg_current_xact_id()::text", String.class);
            String isolation = jdbcTemplate.queryForObject("SHOW transaction_isolation", String.class);
            reads.add(new ReadObservation(transactionId, isolation, seat.getStatus()));
            // Only synchronize the first wave; a retry must be allowed to read without a partner.
            int ordinal = readCount.incrementAndGet();
            if (ordinal <= 2) {
                try {
                    barrier.await(10, TimeUnit.SECONDS);
                    // Both snapshots contain FREE. Let the first reader commit before the second writes,
                    // so a retry is guaranteed to start after that commit, independent of scheduling.
                    if (ordinal == 2 && !firstCommit.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("The winning transaction did not commit");
                    }
                } catch (Exception exception) {
                    throw new IllegalStateException("Serializable read synchronization failed", exception);
                }
            }
        }
    }
}
