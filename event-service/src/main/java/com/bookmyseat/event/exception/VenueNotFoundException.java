package com.bookmyseat.event.exception;

/** Rendered as 404. */
public class VenueNotFoundException extends RuntimeException {

    public VenueNotFoundException(Long id) {
        super("Venue " + id + " not found");
    }
}
