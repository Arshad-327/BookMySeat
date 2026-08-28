package com.bookmyseat.event.exception;

/** Sent by the caller with no role header at all. Rendered as 401. */
public class MissingRoleException extends RuntimeException {

    public MissingRoleException(String header) {
        super("Missing required header: " + header);
    }
}
