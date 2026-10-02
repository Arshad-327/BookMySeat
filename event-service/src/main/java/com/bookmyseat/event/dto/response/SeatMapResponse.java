package com.bookmyseat.event.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * The full seat map for one show, grouped by row, with enough of a header to say
 * WHICH show it is.
 *
 * <h2>Why the header is here at all</h2>
 * This used to carry the showId, the counts and the rows - a seat grid with nothing
 * above it. The endpoint is public, unauthenticated and deep-linkable, so a client
 * arriving at /shows/301 had a page it could render and could not label: the title
 * belongs to the EVENT, and the caller holds a show id, not an event id.
 *
 * <p>{@code startsAt} is load-bearing beyond the header, and that is the reason these
 * three fields landed together. booking-service reads this map on the hold path and
 * could not refuse a booking for a show that had already started, because the answer to
 * "when does it start" was not in the only response it had. See
 * {@code BookingService.hold}.
 *
 * <h2>{@code eventId}, which this javadoc used to defer</h2>
 * The header said which show this is and gave no way to get from it to the event: a client
 * on /shows/301 could print the title and could not link to /events/42. That was left out
 * of the header on purpose, to be settled with the rest of the frontend contract, and it
 * now has been. It costs nothing to serve - {@code findWithEventAndVenueById} already
 * loads the event for its title.
 *
 * <p>It is also what makes a show-level endpoint unnecessary. There is no
 * GET /api/shows/{id}, and with the event id here a client has no question left to ask
 * one: everything else about the event is one GET /api/events/{eventId} away.
 */
@Schema(description = "The full seat map for one show, grouped by row")
public record SeatMapResponse(

        @Schema(example = "301")
        Long showId,

        @Schema(description = "The event this show belongs to - the id GET /api/events/{id} takes. "
                + "How a client navigates up from a show page.",
                example = "42")
        Long eventId,

        @Schema(description = "What the ticket is for - the EVENT's title, not the show's",
                example = "Coldplay - Music of the Spheres")
        String eventTitle,

        @Schema(description = "The venue's name only - no address, no city",
                example = "DY Patil Stadium")
        String venueName,

        @Schema(description = "Start time, always UTC with a trailing Z (CLAUDE.md Timekeeping). "
                + "Rendering it in the viewer's zone is the client's concern; the wire stays "
                + "an instant.",
                example = "2026-09-14T18:30:00Z")
        Instant startsAt,

        @Schema(description = "Every seat in the map, booked included", example = "120")
        int totalSeats,

        @Schema(
                description = "Seats with status AVAILABLE. Counted from the same snapshot, "
                        + "so it can be stale the moment it is read - it is a display hint, "
                        + "never a reservation.",
                example = "97")
        int availableSeats,

        @Schema(description = "Rows ordered by row label")
        List<SeatRowResponse> rows
) {
}
