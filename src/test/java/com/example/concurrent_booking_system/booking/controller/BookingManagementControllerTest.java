package com.example.concurrent_booking_system.booking.controller;

import com.example.concurrent_booking_system.booking.BookingStatus;
import com.example.concurrent_booking_system.booking.dto.BookingResponseDto;
import com.example.concurrent_booking_system.booking.service.BookingManagementService;
import com.example.concurrent_booking_system.exception.BookingAccessDeniedException;
import com.example.concurrent_booking_system.exception.BookingCancellationTimeoutException;
import com.example.concurrent_booking_system.exception.BookingNotFoundException;
import com.example.concurrent_booking_system.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BookingManagementControllerTest {

    private final BookingManagementService service = mock(BookingManagementService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new BookingManagementController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void returnsBookingDtos() throws Exception {
        when(service.getUserBookings(2L)).thenReturn(List.of(
                new BookingResponseDto(5L, 2L, 3L, BookingStatus.CANCELLED, OffsetDateTime.now(), null)));

        mvc.perform(get("/api/users/2/bookings"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"status\":\"CANCELLED\"")));
    }

    @Test
    void cancellationReturnsNoContentAndPassesExplicitUser() throws Exception {
        mvc.perform(delete("/api/bookings/5").param("userId", "2"))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        verify(service).cancelBooking(5L, 2L);
    }

    @Test
    void rejectsMissingOrMalformedParametersBeforeCallingService() throws Exception {
        mvc.perform(delete("/api/bookings/5"))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("INVALID_REQUEST")));
        mvc.perform(delete("/api/bookings/5").param("userId", "invalid"))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("INVALID_REQUEST")));
        mvc.perform(delete("/api/bookings/invalid").param("userId", "2"))
                .andExpect(status().isBadRequest()).andExpect(content().string(containsString("INVALID_REQUEST")));
        verifyNoInteractions(service);
    }

    @Test
    void returnsForbiddenForAnotherOwnersBooking() throws Exception {
        doThrow(new BookingAccessDeniedException(5L)).when(service).cancelBooking(5L, 2L);
        mvc.perform(delete("/api/bookings/5").param("userId", "2"))
                .andExpect(status().isForbidden()).andExpect(content().string(containsString("BOOKING_ACCESS_DENIED")));
    }

    @Test
    void returnsNotFoundForMissingBooking() throws Exception {
        doThrow(new BookingNotFoundException(5L)).when(service).cancelBooking(5L, 2L);
        mvc.perform(delete("/api/bookings/5").param("userId", "2"))
                .andExpect(status().isNotFound()).andExpect(content().string(containsString("BOOKING_NOT_FOUND")));
    }

    @Test
    void returnsServiceUnavailableForCancellationTimeout() throws Exception {
        doThrow(new BookingCancellationTimeoutException(5L, 2000, new RuntimeException("Timeout")))
                .when(service).cancelBooking(5L, 2L);
        mvc.perform(delete("/api/bookings/5").param("userId", "2"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(containsString("BOOKING_CANCELLATION_TIMEOUT")));
    }
}
