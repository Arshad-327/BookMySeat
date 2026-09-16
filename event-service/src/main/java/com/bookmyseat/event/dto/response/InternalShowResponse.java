package com.bookmyseat.event.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * Everything a booking-confirmation message needs about a show, in one response.
 *
 * <h2>A read model, shaped for one known caller</h2>
 * This is not a general "show by id" resource, and the difference is the point. Rendering
 * one confirmation email otherwise takes two calls - GET /api/shows/{id}/seats for the
 * labels, GET /api/events/{eventId} for the title, which the caller cannot even address
 * because it holds a show id, not an event id. Two round trips to build one message is the
 * shape that turns into an N+1 the moment anything sends in batches.
 *
 * <p>The cost of a read model is that it is coupled to its consumer: fields exist here
 * because notification-service prints them. That is the honest trade, and it is why the
 * projection is narrow - no price, no seat status, no other shows, no venue address. A read
 * model that drifts into a general-purpose endpoint has given up the only property that
 * justified adding it, and InternalShowEndpointMySqlTest asserts the exact body to make
 * that drift visible.
 *
 * @param showId     the show, as requested
 * @param eventTitle what the ticket is for, e.g. "Coldplay - Music of the Spheres"
 * @param venueName  the venue's name only - not its address or city
 * @param startsAt   UTC with a trailing Z, per CLAUDE.md Timekeeping. The RECIPIENT'S zone is
 *                   the consumer's concern, not this service's: the wire stays an instant
 * @param seats      labels for exactly the ids asked about, in the order the seats sit in the
 *                   venue. Ids that do not belong to this show are absent rather than an error
 */
@Schema(description = "One show, projected for a confirmation message")
public record InternalShowResponse(

        @Schema(example = "301")
        Long showId,

        @Schema(example = "Coldplay - Music of the Spheres")
        String eventTitle,

        @Schema(example = "DY Patil Stadium")
        String venueName,

        @Schema(description = "Start time, always UTC with a trailing Z (CLAUDE.md Timekeeping)",
                example = "2026-09-14T18:30:00Z")
        Instant startsAt,

        @Schema(description = "Labels for the requested seat ids only")
        List<InternalSeatLabelResponse> seats
) {
}
