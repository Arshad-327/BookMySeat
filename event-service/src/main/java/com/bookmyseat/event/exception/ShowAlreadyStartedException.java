package com.bookmyseat.event.exception;

import java.time.Instant;

/**
 * A seat was asked to be sold for a show that has already started. Rendered as 409.
 *
 * <h2>409, not 400</h2>
 * The request is well formed and every id in it is real: what refuses it is the state the
 * show is in, and no edit to the body can fix that. That is the same distinction the rest
 * of this service draws - {@link SeatsAlreadyBookedException} and
 * {@link SeatsAlreadyExistException} are 409 for the same reason, while 400 is kept for a
 * body that is wrong in itself.
 *
 * <h2>A sharp boundary, deliberately</h2>
 * Refused from {@code startsAt} inclusive: at the stroke of the start time the seats stop
 * being sellable. There is no grace period, and adding one would be a product decision
 * nobody has made - "doors closed" is a rule about a venue, not about a timestamp
 * comparison. A fuzzy boundary is also the harder one to test, and an off-by-one in it
 * would mean selling a ticket to a show that is already running.
 *
 * <p>The comparison is made against the injected {@link java.time.Clock}, never SQL
 * {@code NOW()} (CLAUDE.md Timekeeping), so the boundary can be pinned to the microsecond
 * in a test.
 */
public class ShowAlreadyStartedException extends RuntimeException {

    public ShowAlreadyStartedException(Long showId, Instant startsAt) {
        super("Show " + showId + " started at " + startsAt + " and can no longer be booked");
    }
}
