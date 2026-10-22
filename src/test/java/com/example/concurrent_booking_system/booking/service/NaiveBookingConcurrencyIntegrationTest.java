package com.example.concurrent_booking_system.booking.service;

import com.example.concurrent_booking_system.booking.BookingStatus;
import com.example.concurrent_booking_system.booking.dto.BookingResponseDto;
import com.example.concurrent_booking_system.seat.repository.SeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
@Testcontainers
@Import(NaiveBookingConcurrencyIntegrationTest.BarrierTestConfiguration.class)
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=4",
        "spring.datasource.hikari.minimum-idle=0"
})
class NaiveBookingConcurrencyIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
            .withDatabaseName("booking_test")
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

    @Autowired
    private SeatReadBarrier seatReadBarrier;

    private Long eventId;
    private Long seatId;
    private Long firstUserId;
    private Long secondUserId;

    @BeforeEach
    void createTestData() {
        eventId = jdbcTemplate.queryForObject(
                """
                INSERT INTO events (name, description, start_time)
                VALUES ('Concurrency test event', 'Test data', CURRENT_TIMESTAMP)
                RETURNING id
                """,
                Long.class
        );

        seatId = jdbcTemplate.queryForObject(
                """
                INSERT INTO seats (event_id, row_number, seat_number, status, price)
                VALUES (?, 'A', 1, 'FREE', 1500.00)
                RETURNING id
                """,
                Long.class,
                eventId
        );

        firstUserId = createUser();
        secondUserId = createUser();
    }

    @Test
    void createsTwoActiveBookingsWhenBothTransactionsReadFreeSeat() throws Exception {
        seatReadBarrier.enableFor(seatId);

        CountDownLatch workersReady = new CountDownLatch(2);
        CountDownLatch startSignal = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<BookingResponseDto> firstAttempt = executor.submit(
                    () -> bookAfterStart(workersReady, startSignal, firstUserId)
            );
            Future<BookingResponseDto> secondAttempt = executor.submit(
                    () -> bookAfterStart(workersReady, startSignal, secondUserId)
            );

            assertTrue(workersReady.await(10, TimeUnit.SECONDS));
            startSignal.countDown();

            BookingResponseDto firstBooking = firstAttempt.get(30, TimeUnit.SECONDS);
            BookingResponseDto secondBooking = secondAttempt.get(30, TimeUnit.SECONDS);

            assertNotNull(firstBooking.id());
            assertNotNull(secondBooking.id());
            assertEquals(BookingStatus.ACTIVE, firstBooking.status());
            assertEquals(BookingStatus.ACTIVE, secondBooking.status());
            assertEquals(2L, countActiveBookings());
            assertEquals("BOOKED", getSeatStatus());
        } finally {
            startSignal.countDown();
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private Long createUser() {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO users (email, password_hash)
                VALUES (?, 'test-placeholder')
                RETURNING id
                """,
                Long.class,
                UUID.randomUUID() + "@example.test"
        );
    }

    private BookingResponseDto bookAfterStart(
            CountDownLatch workersReady,
            CountDownLatch startSignal,
            Long userId
    ) throws InterruptedException {
        workersReady.countDown();

        if (!startSignal.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Start signal timed out");
        }

        return bookingService.bookSeat(eventId, seatId, userId);
    }

    private long countActiveBookings() {
        Long count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM bookings
                WHERE seat_id = ?
                  AND status = 'ACTIVE'
                """,
                Long.class,
                seatId
        );

        assertNotNull(count);
        return count;
    }

    private String getSeatStatus() {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM seats WHERE id = ?",
                String.class,
                seatId
        );
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class BarrierTestConfiguration {

        @Bean
        SeatReadBarrier seatReadBarrier() {
            return new SeatReadBarrier();
        }

        @Bean
        BeanPostProcessor seatRepositoryBarrierPostProcessor(SeatReadBarrier seatReadBarrier) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof SeatRepository) || !beanName.equals("seatRepository")) {
                        return bean;
                    }

                    ProxyFactory proxyFactory = new ProxyFactory(bean);
                    proxyFactory.addInterface(SeatRepository.class);
                    proxyFactory.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> {
                        Object result = invocation.proceed();

                        if (invocation.getMethod().getName().equals("findById")) {
                            seatReadBarrier.awaitIfEnabled((Long) invocation.getArguments()[0]);
                        }

                        return result;
                    });
                    return proxyFactory.getProxy();
                }
            };
        }
    }

    static class SeatReadBarrier {

        private volatile Long targetSeatId;
        private volatile CyclicBarrier barrier;

        void enableFor(Long seatId) {
            targetSeatId = seatId;
            barrier = new CyclicBarrier(2);
        }

        void awaitIfEnabled(Long loadedSeatId) {
            if (!loadedSeatId.equals(targetSeatId)) {
                return;
            }

            try {
                barrier.await(10, TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new IllegalStateException("Seat read synchronization failed", exception);
            }
        }
    }
}
