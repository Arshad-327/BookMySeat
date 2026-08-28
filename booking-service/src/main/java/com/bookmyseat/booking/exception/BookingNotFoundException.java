package com.bookmyseat.booking.exception;

/** Rendered as 404. */
public class BookingNotFoundException extends RuntimeException {

    public BookingNotFoundException(Long id) {
        super("Booking " + id + " not found");
    }
}
