package com.example.concurrent_booking_system.booking.controller;

import com.example.concurrent_booking_system.booking.BookingStatus;
import com.example.concurrent_booking_system.booking.dto.BookingResponseDto;
import com.example.concurrent_booking_system.booking.service.BookingService;
import com.example.concurrent_booking_system.exception.GlobalExceptionHandler;
import com.example.concurrent_booking_system.exception.InvalidBookingRequestException;
import com.example.concurrent_booking_system.exception.SeatAlreadyBookedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BookingControllerTest {

    private final BookingService bookingService = mock(BookingService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new BookingController(bookingService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void returnsCreatedBooking() throws Exception {
        when(bookingService.bookSeat(1L, 3L, 2L)).thenReturn(new BookingResponseDto(
                5L, 2L, 3L, BookingStatus.ACTIVE, OffsetDateTime.now(ZoneOffset.UTC), null
        ));

        mockMvc.perform(post("/api/events/1/seats/3/book")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":2}"))
                .andExpect(status().isCreated())
                .andExpect(content().string(containsString("\"status\":\"ACTIVE\"")));
    }

    @Test
    void returnsBadRequestForMissingUserId() throws Exception {
        when(bookingService.bookSeat(1L, 3L, null))
                .thenThrow(new InvalidBookingRequestException("userId must be a positive number"));

        mockMvc.perform(post("/api/events/1/seats/3/book")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("INVALID_REQUEST")));
    }

    @Test
    void returnsBadRequestForInvalidJson() throws Exception {
        mockMvc.perform(post("/api/events/1/seats/3/book")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("INVALID_REQUEST")));
    }

    @Test
    void returnsConflictForBookedSeat() throws Exception {
        when(bookingService.bookSeat(1L, 3L, 2L)).thenThrow(new SeatAlreadyBookedException(3L));

        mockMvc.perform(post("/api/events/1/seats/3/book")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":2}"))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("SEAT_ALREADY_BOOKED")));
    }
}
