package com.bookmyseat.booking.exception;

import java.util.List;

/**
 * Confirm was called but this booking no longer holds its seats. Rendered as 409.
 *
 * <p>Two ways to get here, and the caller cannot usefully tell them apart: the
 * ten-minute TTL lapsed, or the key now carries a different booking id because
 * someone else took the seat after the hold expired. Either way the booking
 * cannot be confirmed and the user has to start again.
 */
public class HoldExpiredException extends RuntimeException {

    public HoldExpiredException(Long bookingId, List<Long> seatIds) {
        super("Booking " + bookingId + " no longer holds seat(s) " + seatIds
                + "; the hold expired or was taken by another booking");
    }

    public HoldExpiredException(Long bookingId) {
        super("Booking " + bookingId + " has expired and can no longer be confirmed");
    }
}
