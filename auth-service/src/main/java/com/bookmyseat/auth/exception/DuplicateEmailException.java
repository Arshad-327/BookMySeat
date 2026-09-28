package com.bookmyseat.auth.exception;

/**
 * The email is already registered. Rendered as 409.
 *
 * <p>Thrown from two places in {@code AuthService.register} and carrying the same message
 * from both: the pre-check that reads {@code existsByEmail}, and the translation of the
 * UNIQUE index refusing the insert when two concurrent registrations both passed that check.
 * A caller cannot tell them apart, which is deliberate - the difference is a race it did not
 * take part in.
 */
public class DuplicateEmailException extends RuntimeException {

    public DuplicateEmailException(String message) {
        super(message);
    }

    /**
     * With the constraint violation that caused it.
     *
     * <p>The cause is kept for the log, not for the response: the 409 body carries only the
     * message. A translated exception that drops its cause turns a database refusal into an
     * assertion nobody can trace back to a constraint name.
     */
    public DuplicateEmailException(String message, Throwable cause) {
        super(message, cause);
    }
}
