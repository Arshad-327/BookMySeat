package com.bookmyseat.booking.exception;

import com.bookmyseat.booking.entity.BookingStatus;

/**
 * Confirm was called on a booking that is not PENDING. Rendered as 409.
 *
 * <p>Covers the double-confirm case (already CONFIRMED) as well as CANCELLED and
 * EXPIRED. Reported rather than treated as idempotent success: a second confirm
 * means the caller believes something that is not true about this booking.
 */
public class BookingNotPendingException extends RuntimeException {

    public BookingNotPendingException(Long bookingId, BookingStatus status) {
        super("Booking " + bookingId + " is " + status + ", not PENDING; only a PENDING booking can be confirmed");
    }
}
