package com.bookmyseat.booking.exception;

import com.bookmyseat.booking.entity.BookingStatus;

/**
 * Confirm or cancel was called on a booking that is not PENDING. Rendered as 409.
 *
 * <p>Covers the double-confirm and double-cancel cases as well as every other
 * transition out of a finished state. Reported rather than treated as idempotent
 * success: a second confirm or cancel means the caller believes something that is not
 * true about this booking.
 */
public class BookingNotPendingException extends RuntimeException {

    /**
     * @param action what was attempted, as a past participle - "confirmed" or "cancelled"
     */
    public BookingNotPendingException(Long bookingId, BookingStatus status, String action) {
        super("Booking " + bookingId + " is " + status + ", not PENDING; only a PENDING booking can be " + action);
    }
}
