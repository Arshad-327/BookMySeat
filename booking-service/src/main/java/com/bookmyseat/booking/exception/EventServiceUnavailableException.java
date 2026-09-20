package com.bookmyseat.booking.exception;

/**
 * event-service could not be reached or failed. Rendered as 503.
 *
 * <h2>Why the user-facing wording is carried on the exception</h2>
 * One handler renders this, and the sentence it used to render - "event-service is
 * unavailable, please retry" - is true on one of its two paths and false on the other.
 *
 * <ul>
 *   <li><b>The hold path.</b> Nothing was written anywhere, so "retry" means retry the
 *       whole thing, cleanly. The default wording says that correctly.
 *   <li><b>The confirm path.</b> The call that failed is the one that marks the seats
 *       BOOKED, and a read timeout does NOT mean it did not happen - review finding #1 is
 *       precisely the case where event-service commits after the caller gives up. Telling
 *       the user that event-service is unavailable implies nothing changed, which is the
 *       one thing this service does not know.
 * </ul>
 *
 * <p>So the wording travels with the throw site, which knows which path it is on, instead
 * of the handler guessing from a path or a new exception type. No subclass and no branch
 * in the handler: a second type would have to be caught somewhere, and a branch on the
 * request URI would put the knowledge in the place with the least of it.
 */
public class EventServiceUnavailableException extends RuntimeException {

    /** True wherever nothing was written - the hold path. Left as the default deliberately. */
    private static final String DEFAULT_USER_MESSAGE = "event-service is unavailable, please retry";

    private final String userMessage;

    public EventServiceUnavailableException(String message, Throwable cause) {
        this(message, cause, DEFAULT_USER_MESSAGE);
    }

    public EventServiceUnavailableException(String message, Throwable cause, String userMessage) {
        super(message, cause);
        this.userMessage = userMessage;
    }

    /**
     * What the caller is told. Never {@link #getMessage()}, which names the show and the
     * downstream and belongs in the log.
     */
    public String getUserMessage() {
        return userMessage;
    }
}
