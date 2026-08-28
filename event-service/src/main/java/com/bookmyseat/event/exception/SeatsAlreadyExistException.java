package com.bookmyseat.event.exception;

/** Rendered as 409. Seat generation is not additive - a venue's seat map is generated once. */
public class SeatsAlreadyExistException extends RuntimeException {

    public SeatsAlreadyExistException(Long venueId) {
        super("Venue " + venueId + " already has seats; generating again would duplicate them");
    }
}
