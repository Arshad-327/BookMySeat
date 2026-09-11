package com.bookmyseat.event.exception;

import java.util.List;

/**
 * Some requested show_seats ids do not exist in this show. Rendered as 404.
 *
 * <p>This is the short count made into an error. The lookup is scoped to the show,
 * so an id comes back missing when it is unknown or belongs to a different show;
 * either way the caller asked for seats this call cannot book, and the call fails
 * rather than booking the rest and reporting a smaller number.
 */
public class ShowSeatsNotFoundException extends RuntimeException {

    public ShowSeatsNotFoundException(Long showId, List<Long> showSeatIds) {
        super("Show " + showId + " has no seat(s) " + showSeatIds
                + " - unknown ids, or ids belonging to another show");
    }
}
