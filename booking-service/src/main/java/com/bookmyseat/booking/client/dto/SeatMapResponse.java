package com.bookmyseat.booking.client.dto;

import java.time.Instant;
import java.util.List;

/**
 * Mirror of event-service's seat map response.
 *
 * <p>Duplicated rather than shared: a common DTO module would couple the two
 * services' deploy cycles, and CLAUDE.md keeps services independent. Unknown
 * fields are ignored by Jackson, so event-service can add fields without breaking
 * this.
 *
 * <p>A PARTIAL mirror, and deliberately so. event-service's map also carries
 * {@code eventTitle} and {@code venueName} for the client that renders the grid; nothing
 * in this service prints them, so they are not mirrored here. {@code startsAt} is, because
 * the hold path makes a decision with it.
 *
 * @param startsAt when the show begins, as a UTC instant. Not decoration:
 *                 {@code BookingService.hold} refuses a booking for a show that has already
 *                 started, and this is where that fact enters this service. An Instant and
 *                 never a LocalDateTime, per CLAUDE.md Timekeeping - a local date-time on
 *                 the wire would be read in the host's zone, so the comparison would be
 *                 wrong by hours on a developer machine while looking right in the container
 */
public record SeatMapResponse(
        Long showId,
        Instant startsAt,
        int totalSeats,
        int availableSeats,
        List<SeatRowResponse> rows
) {
}
