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
 * <p>This used to be a deliberately PARTIAL mirror: {@code eventTitle} and
 * {@code venueName} were left out because nothing in this service printed them. That
 * stopped being true when a booking began to carry what it is for. They are mirrored now,
 * with {@code eventId}, because {@code BookingService.hold} copies them onto the booking
 * row - they were already on the wire, in the one response hold reads.
 *
 * <h2>Two kinds of field, and they fail differently</h2>
 * <b>Fail on what you compute with, degrade on what you print.</b> {@code startsAt} and
 * {@code rows} are computed with - a decision and a price depend on them - so a response
 * without either is unusable and {@code EventClient} answers 503. {@code eventId},
 * {@code eventTitle} and {@code venueName} are printed and nothing else; a response
 * without them is still a seat map, and the hold goes ahead storing NULL.
 *
 * @param eventId    the event this show belongs to. Display only; may be null
 * @param eventTitle what the ticket is for. Display only; may be null
 * @param venueName  the venue's name. Display only; may be null
 * @param startsAt   when the show begins, as a UTC instant. Not decoration:
 *                   {@code BookingService.hold} refuses a booking for a show that has already
 *                   started, and this is where that fact enters this service. An Instant and
 *                   never a LocalDateTime, per CLAUDE.md Timekeeping - a local date-time on
 *                   the wire would be read in the host's zone, so the comparison would be
 *                   wrong by hours on a developer machine while looking right in the container
 */
public record SeatMapResponse(
        Long showId,
        Long eventId,
        String eventTitle,
        String venueName,
        Instant startsAt,
        int totalSeats,
        int availableSeats,
        List<SeatRowResponse> rows
) {
}
