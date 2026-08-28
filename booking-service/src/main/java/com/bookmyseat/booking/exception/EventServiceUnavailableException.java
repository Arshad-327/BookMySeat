package com.bookmyseat.booking.exception;

/** event-service could not be reached or failed. Rendered as 503. */
public class EventServiceUnavailableException extends RuntimeException {

    public EventServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
