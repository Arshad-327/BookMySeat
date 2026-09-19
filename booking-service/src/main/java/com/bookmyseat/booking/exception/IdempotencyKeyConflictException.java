package com.bookmyseat.booking.exception;

/**
 * An Idempotency-Key was presented by a user other than the one whose booking it
 * created. Rendered as 409.
 *
 * <p>Keys are chosen by the client, so nothing stops two callers presenting the same
 * one - a copied sample request is the likeliest cause, a deliberate probe the
 * worrying one. Either way the replay cannot be served: handing over the booking
 * would disclose another user's seats, total and booking id.
 *
 * <p>The message names the key, which the caller already sent, and nothing about the
 * booking or the user it belongs to.
 *
 * <h2>The second form: one confirm key, two booking ids</h2>
 * The same 409 covers a confirm whose key resolves to a booking other than the {id} in
 * the path. It is the same class of fault - a key presented for something it does not
 * name - and the caller has to do the same thing about it: stop reusing the key. Giving
 * it a status code of its own would say these are different problems to a client that
 * has to handle them identically.
 *
 * <p>That message does name the cached booking id, which the first form deliberately
 * does not. It is safe here and only here: ownership is checked before this is thrown,
 * so the booking being named already belongs to the caller, and without the id the
 * message could not say which of the two bookings the key really belongs to - which is
 * the one thing the client needs in order to fix the bug.
 */
public class IdempotencyKeyConflictException extends RuntimeException {

    public IdempotencyKeyConflictException(String idempotencyKey) {
        super("Idempotency-Key " + idempotencyKey + " was already used by a different user");
    }

    public IdempotencyKeyConflictException(
            String idempotencyKey, Long confirmedBookingId, Long requestedBookingId) {
        super("Idempotency-Key " + idempotencyKey + " was already used to confirm booking "
                + confirmedBookingId + ", not booking " + requestedBookingId);
    }
}
