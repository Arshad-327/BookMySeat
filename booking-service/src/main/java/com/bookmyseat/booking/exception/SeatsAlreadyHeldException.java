package com.bookmyseat.booking.exception;

import java.util.List;

/**
 * One or more seats are currently held by a different booking. Rendered as 409
 * with the conflicting ids in the body, not folded into a message string.
 *
 * <p>Unlike {@link SeatNotAvailableException}, which reports what event-service
 * said at read time and guarantees nothing, this one is authoritative: the hold
 * script tried to take these seats atomically and lost. Nothing this booking
 * asked for is held now - hold_seats.lua rolled back whatever it had acquired.
 */
public class SeatsAlreadyHeldException extends RuntimeException {

    private final List<Long> conflictingSeatIds;

    public SeatsAlreadyHeldException(List<Long> conflictingSeatIds) {
        super("Seats are currently held by another booking: " + conflictingSeatIds);
        this.conflictingSeatIds = List.copyOf(conflictingSeatIds);
    }

    public List<Long> getConflictingSeatIds() {
        return conflictingSeatIds;
    }
}
