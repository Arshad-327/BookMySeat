package com.bookmyseat.notification.exception;

/**
 * A REQUIRED lookup failed for a reason that may not be true a moment from now: a connection
 * refused, a timeout, a 5xx. auth-service is unavailable; the user still exists.
 *
 * <p>Thrown out of the listener, deliberately. The container commits offsets only after the
 * listener returns normally, so escaping is what leaves the offset where it was and brings
 * the event back. Catching this to "handle it gracefully" would turn a delayed email into a
 * lost one.
 */
public class TransientLookupException extends RuntimeException {

    public TransientLookupException(String message, Throwable cause) {
        super(message, cause);
    }
}
