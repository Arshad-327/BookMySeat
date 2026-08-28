package com.bookmyseat.event.exception;

/**
 * Rendered as 409.
 *
 * <p>This is the guard that keeps "a show cannot exist without seats" true, and it
 * is load-bearing for a decision made elsewhere: GET /api/shows/{id}/seats runs
 * exactly one SQL statement and performs no existence check on the show, reporting
 * an empty result as 404. That is only honest while a seatless show is impossible
 * to create. Remove this guard and that 404 starts lying about shows that really
 * do exist. See ShowService#findSeatMap.
 */
public class VenueHasNoSeatsException extends RuntimeException {

    public VenueHasNoSeatsException(Long venueId) {
        super("Venue " + venueId + " has no seats; generate seats for the venue "
                + "before creating a show there");
    }
}
