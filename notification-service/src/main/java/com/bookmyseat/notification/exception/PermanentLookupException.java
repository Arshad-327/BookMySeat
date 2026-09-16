package com.bookmyseat.notification.exception;

/**
 * A REQUIRED lookup failed in a way that no retry can change.
 *
 * <p>Two distinguishable causes, kept apart because they need different humans:
 * <ul>
 *   <li><b>404</b> - the user is deleted. The data is odd; nothing is broken.</li>
 *   <li><b>401/403</b> - this service is not permitted to ask. Nothing is odd about the data;
 *       notification-service is misconfigured and someone must fix it.</li>
 * </ul>
 *
 * <p>Caught inside the listener rather than thrown out of it, which is the whole distinction
 * from {@link TransientLookupException}. See BookingConfirmedListener for why retrying
 * something that can never succeed is an outage rather than caution.
 *
 * @param status the HTTP status that produced this verdict; carried so the ERROR log can name
 *               it, since that log line is the only record the message existed
 */
public class PermanentLookupException extends RuntimeException {

    private final int status;

    public PermanentLookupException(String message, int status) {
        super(message);
        this.status = status;
    }

    public int getStatus() {
        return status;
    }

    /** True when this service itself is the problem, rather than the user being absent. */
    public boolean isMisconfiguration() {
        return status == 401 || status == 403;
    }
}
