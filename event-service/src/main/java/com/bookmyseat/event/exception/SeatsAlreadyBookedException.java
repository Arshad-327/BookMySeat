package com.bookmyseat.event.exception;

import java.util.List;

/**
 * One or more requested seats are BOOKED to somebody else. Rendered as 409.
 *
 * <p>Thrown by the acceptance guard in InternalSeatService before anything is written,
 * so the whole request is refused and no seat in it changes. It used to be a
 * silent skip that the caller could only notice as a lower count.
 *
 * <p>"by another booking" is load-bearing in the message, and covers two cases that read
 * the same to the caller and should: the seat is owned by a different booking id, or it is
 * BOOKED with no owner recorded at all - a row written before V2__seat_booking_owner.sql.
 * Neither is this caller's seat. A seat BOOKED to the requesting booking is NOT an error
 * and never reaches here; it is accepted, because the call is idempotent.
 */
public class SeatsAlreadyBookedException extends RuntimeException {

    public SeatsAlreadyBookedException(Long showId, List<Long> showSeatIds) {
        super("Show " + showId + ": seat(s) " + showSeatIds
                + " are already BOOKED by another booking.");
    }
}
