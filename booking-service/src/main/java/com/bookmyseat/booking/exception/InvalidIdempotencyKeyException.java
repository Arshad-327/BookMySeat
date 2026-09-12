package com.bookmyseat.booking.exception;

/**
 * The Idempotency-Key header was present but is not a UUID. Rendered as 400.
 *
 * <p>A UUID is required rather than any opaque string so that keys from different
 * clients cannot collide by construction - two clients both sending "retry-1" would
 * otherwise be handed each other's bookings, or refused as
 * {@link IdempotencyKeyConflictException}. The format is also what keeps the value
 * inside bookings.idempotency_key VARCHAR(64): a canonical UUID is 36 characters.
 */
public class InvalidIdempotencyKeyException extends RuntimeException {

    public InvalidIdempotencyKeyException(String idempotencyKey) {
        super("Idempotency-Key must be a UUID, got: " + idempotencyKey);
    }
}
