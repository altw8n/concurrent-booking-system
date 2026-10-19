package com.example.concurrent_booking_system.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(EventNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiErrorResponse handleEventNotFound(EventNotFoundException exception) {
        return new ApiErrorResponse("EVENT_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(UserNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiErrorResponse handleUserNotFound(UserNotFoundException exception) {
        return new ApiErrorResponse("USER_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(SeatNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiErrorResponse handleSeatNotFound(SeatNotFoundException exception) {
        return new ApiErrorResponse("SEAT_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(SeatAlreadyBookedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiErrorResponse handleSeatAlreadyBooked(SeatAlreadyBookedException exception) {
        return new ApiErrorResponse("SEAT_ALREADY_BOOKED", exception.getMessage());
    }

    @ExceptionHandler(InvalidBookingRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiErrorResponse handleInvalidBookingRequest(InvalidBookingRequestException exception) {
        return new ApiErrorResponse("INVALID_REQUEST", exception.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiErrorResponse handleUnreadableRequest(HttpMessageNotReadableException exception) {
        return new ApiErrorResponse("INVALID_REQUEST", "Request body is missing or invalid");
    }
}
