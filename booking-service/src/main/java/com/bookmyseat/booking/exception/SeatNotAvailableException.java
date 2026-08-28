package com.bookmyseat.booking.exception;

import java.util.List;

/**
 * One or more requested seats were not AVAILABLE at the moment they were read.
 * Rendered as 409.
 *
 * <p>Worth being precise about what this does and does not mean. It reflects what
 * event-service reported at read time only. Because nothing is locked or held
 * between that read and the write, a seat can pass this check and still be sold to
 * someone else microseconds later. Seeing this 409 means the race was lost slowly
 * enough to notice; NOT seeing it does not mean the seat was secured.
 */
public class SeatNotAvailableException extends RuntimeException {

    public SeatNotAvailableException(List<Long> seatIds) {
        super("Seats not available: " + seatIds);
    }
}
