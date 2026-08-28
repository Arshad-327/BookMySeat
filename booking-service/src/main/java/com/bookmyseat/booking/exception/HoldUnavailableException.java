package com.bookmyseat.booking.exception;

/**
 * Redis could not be reached, so no seat hold could be taken or verified.
 * Rendered as 503, and no booking is written.
 *
 * <p>This exception exists so the failure cannot be quietly swallowed. A seat
 * hold fails CLOSED - see the class note on
 * {@link com.bookmyseat.booking.service.SeatHoldService} for why that is the
 * opposite of what a rate limiter should do.
 */
public class HoldUnavailableException extends RuntimeException {

    public HoldUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
