package com.bookmyseat.booking.exception;

import java.util.List;

/** Requested seat ids that do not belong to the requested show. Rendered as 400. */
public class UnknownSeatException extends RuntimeException {

    public UnknownSeatException(Long showId, List<Long> seatIds) {
        super("Seats " + seatIds + " do not belong to show " + showId);
    }
}
