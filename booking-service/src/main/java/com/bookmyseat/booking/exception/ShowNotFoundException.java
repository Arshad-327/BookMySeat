package com.bookmyseat.booking.exception;

/** event-service reported no such show. Rendered as 404. */
public class ShowNotFoundException extends RuntimeException {

    public ShowNotFoundException(Long showId) {
        super("Show " + showId + " not found");
    }
}
