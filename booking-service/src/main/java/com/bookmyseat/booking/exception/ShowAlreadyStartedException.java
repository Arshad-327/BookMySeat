package com.bookmyseat.booking.exception;

import java.time.Instant;

/**
 * A hold was asked for on a show that has already started. Rendered as 409.
 *
 * <h2>409, not 400</h2>
 * The request is well formed and names a real show and real seats; what refuses it is the
 * state the show is in, and no edit to the body can change that. This service already
 * draws that line the same way - {@link SeatNotAvailableException},
 * {@link SeatsAlreadyHeldException}, {@link HoldExpiredException} and
 * {@link BookingNotPendingException} are all 409 for a request that is fine about a world
 * that refuses it, while 400 is kept for a body that is wrong in itself
 * ({@link UnknownSeatException}, {@link InvalidIdempotencyKeyException}).
 *
 * <h2>This is the fast refusal, NOT the guarantee</h2>
 * The rule is enforced again in event-service, at the moment the seats are actually marked
 * BOOKED, and that is the check that makes it a rule: a hold taken one second before the
 * show begins survives for another ten minutes, so confirm has to refuse a started show
 * even though the hold was legitimate when it was taken. See
 * {@code InternalSeatService.markBooked} there.
 *
 * <p>The division of labour is the same one the seat statuses already have. The hold path
 * reads a snapshot of event-service's data to give a clean, precise error before anything is
 * written; the authoritative check happens at the write, in the service that owns the data.
 *
 * <h2>No grace period, and a sharp boundary</h2>
 * Refused from {@code startsAt} inclusive, compared against the injected
 * {@link java.time.Clock} and never SQL {@code NOW()} (CLAUDE.md Timekeeping). "Doors
 * closed" is a product decision nobody has made, and a fuzzy boundary is harder to reason
 * about and to test than a sharp one - the same argument the expiry check in
 * {@code BookingService.confirm} makes at greater length about widening a time comparison.
 */
public class ShowAlreadyStartedException extends RuntimeException {

    public ShowAlreadyStartedException(Long showId, Instant startsAt) {
        super("Show " + showId + " started at " + startsAt + " and can no longer be booked");
    }
}
