package com.bookmyseat.booking.exception;

import java.util.List;

/**
 * event-service refused to mark the seats BOOKED at confirm. Rendered as 409.
 *
 * <p>A refusal is a verdict about the seats - one is already BOOKED, lost the
 * optimistic lock, or is not in this show - not an outage, so it must not be
 * reported as the 503 that {@link EventServiceUnavailableException} produces.
 */
public class SeatBookingRejectedException extends RuntimeException {

    public SeatBookingRejectedException(Long showId, List<Long> seatIds, String reason, Throwable cause) {
        super("Seat(s) " + seatIds + " on show " + showId + " could not be booked: " + reason, cause);
    }
}
