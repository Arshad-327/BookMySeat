package com.bookmyseat.event.exception;

import java.util.List;

/**
 * One or more requested seats are already BOOKED. Rendered as 409.
 *
 * <p>Thrown by the status guard in InternalSeatService before anything is written,
 * so the whole request is refused and no seat in it changes. It used to be a
 * silent skip that the caller could only notice as a lower count.
 */
public class SeatsAlreadyBookedException extends RuntimeException {

    public SeatsAlreadyBookedException(Long showId, List<Long> showSeatIds) {
        super("Show " + showId + ": seat(s) " + showSeatIds + " are already BOOKED");
    }
}
