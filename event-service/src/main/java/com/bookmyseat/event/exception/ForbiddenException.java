package com.bookmyseat.event.exception;

/** Caller presented a role, but not one permitted here. Rendered as 403. */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String requiredRole) {
        super("Role " + requiredRole + " is required for this operation");
    }
}
