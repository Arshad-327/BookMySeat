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
 */
public class IdempotencyKeyConflictException extends RuntimeException {

    public IdempotencyKeyConflictException(String idempotencyKey) {
        super("Idempotency-Key " + idempotencyKey + " was already used by a different user");
    }
}
