package com.example.concurrent_booking_system.booking.service;

import com.example.concurrent_booking_system.booking.BookingStatus;
import com.example.concurrent_booking_system.booking.dto.BookingResponseDto;
import com.example.concurrent_booking_system.booking.entity.Booking;
import com.example.concurrent_booking_system.booking.repository.BookingRepository;
import com.example.concurrent_booking_system.exception.*;
import com.example.concurrent_booking_system.seat.entity.OptimisticSeat;
import com.example.concurrent_booking_system.seat.repository.OptimisticSeatRepository;
import com.example.concurrent_booking_system.seat.repository.SeatRepository;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@Import(BookingLifecycleIntegrationTest.ProbeConfiguration.class)
@SpringBootTest(properties = {
        "booking.strategy=atomic",
        "booking.cancellation.lock-timeout-ms=500",
        "spring.datasource.hikari.maximum-pool-size=6",
        "spring.datasource.hikari.minimum-idle=0",
        "spring.jpa.show-sql=true"
})
class BookingLifecycleIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("booking_lifecycle_test").withUsername("test_user").withPassword("test_password");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private BookingService bookingService;
    @Autowired private BookingManagementService management;
    @Autowired private NaiveBookingStrategy naive;
    @Autowired private OptimisticBookingStrategy optimistic;
    @Autowired private PessimisticBookingStrategy pessimistic;
    @Autowired private AtomicBookingStrategy atomic;
    @Autowired private SerializableBookingStrategy serializable;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private WebApplicationContext webContext;
    @Autowired private LifecycleProbe probe;

    private MockMvc mvc;
    private Long eventId;
    private Long seatId;
    private Long firstUserId;
    private Long secondUserId;

    @BeforeEach
    void setUp() {
        probe.reset();
        mvc = MockMvcBuilders.webAppContextSetup(webContext).build();
        eventId = jdbc.queryForObject("""
                INSERT INTO events (name, description, start_time)
                VALUES ('Lifecycle test', 'Test data', CURRENT_TIMESTAMP + INTERVAL '1 day') RETURNING id
                """, Long.class);
        seatId = createSeat(1);
        firstUserId = createUser();
        secondUserId = createUser();
    }

    @ParameterizedTest
    @ValueSource(strings = {"optimistic", "pessimistic", "atomic", "serializable"})
    void supportsCancellationAndRebookingWithoutOldCancellationReleasingNewBooking(String strategy) {
        BookingService service = service(strategy);
        BookingResponseDto first = service.bookSeat(eventId, seatId, firstUserId);
        long bookedVersion = seatVersion();

        management.cancelBooking(first.id(), firstUserId);

        assertEquals("CANCELLED", bookingStatus(first.id()));
        assertSeat("FREE", 0);
        assertEquals(bookedVersion + 1, seatVersion());
        BookingResponseDto second = service.bookSeat(eventId, seatId, secondUserId);
        long rebookedVersion = seatVersion();
        management.cancelBooking(first.id(), firstUserId);

        assertSeat("BOOKED", 1);
        assertEquals("ACTIVE", bookingStatus(second.id()));
        assertEquals(rebookedVersion, seatVersion(), "Repeated cancellation must not change the new booking's version");
        assertEquals(2L, totalBookings());
        assertEquals(BookingStatus.CANCELLED, management.getUserBookings(firstUserId).getFirst().status());
        assertEquals(second.id(), management.getUserBookings(secondUserId).getFirst().id());
    }

    @ParameterizedTest
    @ValueSource(strings = {"optimistic", "pessimistic", "atomic", "serializable"})
    void bookingDuringUncommittedCancellationHasAConsistentOutcome(String strategy) throws Exception {
        BookingResponseDto old = service(strategy).bookSeat(eventId, seatId, firstUserId);
        probe.heldReleaseSeat = seatId;
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> cancellation = workers.submit(() -> management.cancelBooking(old.id(), firstUserId));
            assertTrue(probe.released.await(5, TimeUnit.SECONDS));
            Future<BookingResponseDto> contender = workers.submit(
                    () -> service(strategy).bookSeat(eventId, seatId, secondUserId));
            if (strategy.equals("pessimistic")) {
                // SELECT ... FOR NO KEY UPDATE waits and then reads the committed FREE row.
                awaitBlockedBy(probe.blockerPid);
                assertFalse(contender.isDone());
                probe.continueRelease.countDown();
                cancellation.get(10, TimeUnit.SECONDS);
                assertEquals(secondUserId, contender.get(10, TimeUnit.SECONDS).userId());
            } else {
                // Ordinary reads / the conditional UPDATE see the still-committed BOOKED state.
                ExecutionException rejection = assertThrows(ExecutionException.class,
                        () -> contender.get(5, TimeUnit.SECONDS));
                assertInstanceOf(SeatAlreadyBookedException.class, rejection.getCause());
                probe.continueRelease.countDown();
                cancellation.get(10, TimeUnit.SECONDS);
                assertNotNull(service(strategy).bookSeat(eventId, seatId, secondUserId).id());
            }
            assertEquals("CANCELLED", bookingStatus(old.id()));
            assertSeat("BOOKED", 1);
            assertEquals(2L, totalBookings());
        } finally {
            stop(workers);
        }
    }

    @Test
    void listsOnlyRequestedUserWithCancelledHistoryAndDeterministicOrder() {
        BookingResponseDto first = bookingService.bookSeat(eventId, seatId, firstUserId);
        BookingResponseDto second = bookingService.bookSeat(eventId, createSeat(2), firstUserId);
        bookingService.bookSeat(eventId, createSeat(3), secondUserId);
        management.cancelBooking(first.id(), firstUserId);
        jdbc.update("UPDATE bookings SET created_at = TIMESTAMPTZ '2026-01-01 00:00:00+00' WHERE user_id = ?", firstUserId);

        var result = management.getUserBookings(firstUserId);

        assertEquals(List.of(second.id(), first.id()), result.stream().map(BookingResponseDto::id).toList());
        assertEquals(List.of(BookingStatus.ACTIVE, BookingStatus.CANCELLED),
                result.stream().map(BookingResponseDto::status).toList());
        assertTrue(management.getUserBookings(createUser()).isEmpty());
        Long missingUser = createUser();
        jdbc.update("DELETE FROM users WHERE id = ?", missingUser);
        assertThrows(UserNotFoundException.class, () -> management.getUserBookings(missingUser));
    }

    @Test
    void completesLifecycleThroughRealHttpControllersAndDatabase() throws Exception {
        mvc.perform(post("/api/events/{eventId}/seats/{seatId}/book", eventId, seatId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":" + firstUserId + "}"))
                .andExpect(status().isCreated());
        Long bookingId = jdbc.queryForObject("SELECT id FROM bookings WHERE seat_id = ?", Long.class, seatId);
        mvc.perform(get("/api/users/{userId}/bookings", firstUserId))
                .andExpect(status().isOk()).andExpect(content().string(containsString("\"status\":\"ACTIVE\"")));
        mvc.perform(delete("/api/bookings/{bookingId}", bookingId).param("userId", firstUserId.toString()))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        mvc.perform(get("/api/users/{userId}/bookings", firstUserId))
                .andExpect(status().isOk()).andExpect(content().string(containsString("\"status\":\"CANCELLED\"")));
        mvc.perform(post("/api/events/{eventId}/seats/{seatId}/book", eventId, seatId)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":" + secondUserId + "}"))
                .andExpect(status().isCreated());
        mvc.perform(delete("/api/bookings/{bookingId}", bookingId).param("userId", firstUserId.toString()))
                .andExpect(status().isNoContent());
        assertSeat("BOOKED", 1);
        assertEquals(2L, totalBookings());
    }

    @Test
    void rejectsInvalidUnknownAndForeignCancellationWithoutChangingData() throws Exception {
        BookingResponseDto booking = bookingService.bookSeat(eventId, seatId, firstUserId);
        mvc.perform(delete("/api/bookings/{bookingId}", booking.id()).param("userId", secondUserId.toString()))
                .andExpect(status().isForbidden()).andExpect(content().string(containsString("BOOKING_ACCESS_DENIED")));
        mvc.perform(delete("/api/bookings/{bookingId}", booking.id()).param("userId", "0"))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("INVALID_REQUEST")));
        mvc.perform(delete("/api/bookings/0").param("userId", firstUserId.toString()))
                .andExpect(status().isBadRequest());
        Long missingBooking = jdbc.queryForObject("""
                INSERT INTO bookings (user_id, seat_id, status) VALUES (?, ?, 'CANCELLED') RETURNING id
                """, Long.class, firstUserId, seatId);
        jdbc.update("DELETE FROM bookings WHERE id = ?", missingBooking);
        mvc.perform(delete("/api/bookings/{bookingId}", missingBooking).param("userId", firstUserId.toString()))
                .andExpect(status().isNotFound()).andExpect(content().string(containsString("BOOKING_NOT_FOUND")));
        Long missingUser = createUser();
        jdbc.update("DELETE FROM users WHERE id = ?", missingUser);
        mvc.perform(delete("/api/bookings/{bookingId}", booking.id()).param("userId", missingUser.toString()))
                .andExpect(status().isNotFound()).andExpect(content().string(containsString("USER_NOT_FOUND")));
        assertEquals("ACTIVE", bookingStatus(booking.id()));
        assertSeat("BOOKED", 1);
        assertEquals(1L, totalBookings());
    }

    @Test
    void simultaneousCancellationsThatBothReadActiveReleaseSeatOnlyOnce() throws Exception {
        BookingResponseDto booking = bookingService.bookSeat(eventId, seatId, firstUserId);
        probe.racedBooking = booking.id();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = workers.submit(() -> management.cancelBooking(booking.id(), firstUserId));
            Future<?> second = workers.submit(() -> management.cancelBooking(booking.id(), firstUserId));
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            stop(workers);
        }
        assertEquals("CANCELLED", bookingStatus(booking.id()));
        assertSeat("FREE", 0);
        assertEquals(1L, seatVersion(), "The second stale ACTIVE observation must not cause another release");
        assertEquals(1L, totalBookings());
    }

    @Test
    void optimisticWriterCannotReuseFreeSnapshotFromBeforeBookingAndCancellation() throws Exception {
        probe.heldOptimisticSeat = seatId;
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try {
            Future<BookingResponseDto> staleWriter = workers.submit(
                    () -> service("optimistic").bookSeat(eventId, seatId, firstUserId));
            assertTrue(probe.optimisticRead.await(5, TimeUnit.SECONDS));
            BookingResponseDto winner = service("optimistic").bookSeat(eventId, seatId, secondUserId);
            management.cancelBooking(winner.id(), secondUserId);
            assertEquals(2L, seatVersion());
            probe.continueOptimistic.countDown();

            ExecutionException conflict = assertThrows(ExecutionException.class,
                    () -> staleWriter.get(10, TimeUnit.SECONDS));
            assertInstanceOf(BookingConflictException.class, conflict.getCause());
            assertSeat("FREE", 0);
            assertEquals(1L, totalBookings(), "The stale writer's INSERT must roll back");
            assertEquals("CANCELLED", bookingStatus(winner.id()));
        } finally {
            stop(workers);
        }
    }

    @Test
    void rollsBackCancellationAndVersionWhenReleaseFails() {
        BookingResponseDto booking = bookingService.bookSeat(eventId, seatId, firstUserId);
        jdbc.execute("""
                ALTER TABLE seats ADD CONSTRAINT chk_test_cancellation_failure
                CHECK (id <> %d OR status <> 'FREE')
                """.formatted(seatId));
        try {
            var failure = assertThrows(DataIntegrityViolationException.class,
                    () -> management.cancelBooking(booking.id(), firstUserId));
            SQLException sql = assertInstanceOf(SQLException.class, failure.getMostSpecificCause());
            assertEquals("23514", sql.getSQLState());
            assertTrue(sql.getMessage().contains("chk_test_cancellation_failure"));
            assertEquals("ACTIVE", bookingStatus(booking.id()));
            assertSeat("BOOKED", 1);
            assertEquals(0L, seatVersion());
        } finally {
            jdbc.execute("ALTER TABLE seats DROP CONSTRAINT chk_test_cancellation_failure");
        }
        management.cancelBooking(booking.id(), firstUserId);
        assertSeat("FREE", 0);
        assertEquals(1L, seatVersion());
    }

    @Test
    void cancellationTimeoutRollsBackAndNextRequestCanSucceed() throws Exception {
        BookingResponseDto booking = bookingService.bookSeat(eventId, seatId, firstUserId);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService workers = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = workers.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                jdbc.queryForObject("SELECT id FROM seats WHERE id = ? FOR UPDATE", Long.class, seatId);
                locked.countDown();
                await(release);
            }));
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            var failure = assertThrows(BookingCancellationTimeoutException.class,
                    () -> management.cancelBooking(booking.id(), firstUserId));
            assertTrue(hasSqlState(failure, "55P03"));
            assertEquals("ACTIVE", bookingStatus(booking.id()));
            assertSeat("BOOKED", 1);
            assertEquals(0L, seatVersion());
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            management.cancelBooking(booking.id(), firstUserId);
            assertSeat("FREE", 0);
        } finally {
            release.countDown();
            stop(workers);
        }
    }

    @Test
    void doesNotFreeSeatWhileAnotherActiveBookingFromNaiveDataRemains() {
        BookingResponseDto first = bookingService.bookSeat(eventId, seatId, firstUserId);
        // Deliberately simulate the duplicate data that the existing naive race test demonstrates.
        Long duplicate = jdbc.queryForObject("""
                INSERT INTO bookings (user_id, seat_id, status) VALUES (?, ?, 'ACTIVE') RETURNING id
                """, Long.class, secondUserId, seatId);
        management.cancelBooking(first.id(), firstUserId);
        assertSeat("BOOKED", 1);
        management.cancelBooking(duplicate, secondUserId);
        assertSeat("FREE", 0);
        assertEquals(2L, totalBookings());
    }

    private BookingService service(String name) {
        return new BookingService(naive, optimistic, pessimistic, atomic, serializable, name, 0, 0);
    }

    private void assertSeat(String status, long activeBookings) {
        assertEquals(status, jdbc.queryForObject("SELECT status FROM seats WHERE id = ?", String.class, seatId));
        assertEquals(activeBookings, jdbc.queryForObject(
                "SELECT count(*) FROM bookings WHERE seat_id = ? AND status = 'ACTIVE'", Long.class, seatId));
    }

    private String bookingStatus(Long bookingId) {
        return jdbc.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private long seatVersion() {
        return jdbc.queryForObject("SELECT version FROM seats WHERE id = ?", Long.class, seatId);
    }

    private long totalBookings() {
        return jdbc.queryForObject("SELECT count(*) FROM bookings WHERE seat_id = ?", Long.class, seatId);
    }

    private Long createSeat(int number) {
        return jdbc.queryForObject("""
                INSERT INTO seats (event_id, row_number, seat_number, status, price)
                VALUES (?, 'A', ?, 'FREE', 1500.00) RETURNING id
                """, Long.class, eventId, number);
    }

    private Long createUser() {
        return jdbc.queryForObject("INSERT INTO users (email, password_hash) VALUES (?, 'test-placeholder') RETURNING id",
                Long.class, UUID.randomUUID() + "@example.test");
    }

    private void awaitBlockedBy(int pid) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Boolean blocked = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM pg_stat_activity
                    WHERE ? = ANY(pg_blocking_pids(pid)))
                    """, Boolean.class, pid);
            if (Boolean.TRUE.equals(blocked)) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        fail("No database lock wait observed for blocker " + pid);
    }

    private void stop(ExecutorService workers) throws InterruptedException {
        probe.continueRelease.countDown();
        probe.continueOptimistic.countDown();
        workers.shutdownNow();
        assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Test synchronization timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test synchronization interrupted", exception);
        }
    }

    private boolean hasSqlState(Throwable failure, String state) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && state.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean
        LifecycleProbe lifecycleProbe() {
            return new LifecycleProbe();
        }

        @Bean
        static BeanPostProcessor lifecycleRepositoryPostProcessor(
                ObjectProvider<LifecycleProbe> probe, ObjectProvider<JdbcTemplate> jdbc) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    Class<?> repositoryType = switch (name) {
                        case "seatRepository" -> SeatRepository.class;
                        case "bookingRepository" -> BookingRepository.class;
                        case "optimisticSeatRepository" -> OptimisticSeatRepository.class;
                        default -> null;
                    };
                    if (repositoryType == null || !repositoryType.isInstance(bean)) {
                        return bean;
                    }
                    ProxyFactory factory = new ProxyFactory(bean);
                    factory.addInterface(repositoryType);
                    factory.addAdvice((MethodInterceptor) invocation -> {
                        Object result = invocation.proceed();
                        LifecycleProbe state = probe.getObject();
                        String method = invocation.getMethod().getName();
                        if (name.equals("bookingRepository") && method.equals("findById")
                                && result instanceof Optional<?> optional && optional.orElse(null) instanceof Booking booking
                                && booking.getId().equals(state.racedBooking)) {
                            try {
                                state.bookingReads.await(5, TimeUnit.SECONDS);
                            } catch (Exception exception) {
                                throw new IllegalStateException("Cancellation read synchronization failed", exception);
                            }
                        }
                        if (name.equals("seatRepository") && method.equals("releaseIfNoActiveBookings")
                                && invocation.getArguments()[0].equals(state.heldReleaseSeat)
                                && state.holdRelease.compareAndSet(true, false)) {
                            state.blockerPid = jdbc.getObject().queryForObject("SELECT pg_backend_pid()", Integer.class);
                            state.released.countDown();
                            await(state.continueRelease);
                        }
                        if (name.equals("optimisticSeatRepository") && method.equals("findById")
                                && result instanceof Optional<?> optional
                                && optional.orElse(null) instanceof OptimisticSeat seat
                                && seat.getId().equals(state.heldOptimisticSeat)
                                && state.holdOptimistic.compareAndSet(true, false)) {
                            state.optimisticRead.countDown();
                            await(state.continueOptimistic);
                        }
                        return result;
                    });
                    return factory.getProxy();
                }
            };
        }
    }

    static class LifecycleProbe {
        volatile Long racedBooking;
        volatile Long heldReleaseSeat;
        volatile Long heldOptimisticSeat;
        volatile int blockerPid;
        CyclicBarrier bookingReads;
        CountDownLatch released;
        CountDownLatch continueRelease;
        CountDownLatch optimisticRead;
        CountDownLatch continueOptimistic;
        final AtomicBoolean holdRelease = new AtomicBoolean();
        final AtomicBoolean holdOptimistic = new AtomicBoolean();

        void reset() {
            racedBooking = null;
            heldReleaseSeat = null;
            heldOptimisticSeat = null;
            bookingReads = new CyclicBarrier(2);
            released = new CountDownLatch(1);
            continueRelease = new CountDownLatch(1);
            optimisticRead = new CountDownLatch(1);
            continueOptimistic = new CountDownLatch(1);
            holdRelease.set(true);
            holdOptimistic.set(true);
        }
    }
}
