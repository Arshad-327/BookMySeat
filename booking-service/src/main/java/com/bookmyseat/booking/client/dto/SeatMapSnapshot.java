package com.bookmyseat.booking.client.dto;

import java.time.Instant;
import java.util.Map;

/**
 * One read of event-service's seat map, indexed for the hold path.
 *
 * <h2>A SNAPSHOT, and the name is the warning</h2>
 * Everything in here was true at the moment event-service answered and nothing holds it
 * true afterwards. A seat that reads AVAILABLE here can be held by somebody else
 * microseconds later; the Redis hold is what decides, not this. See
 * {@code BookingService.hold}, which says the same thing at greater length about the seat
 * statuses.
 *
 * <p>{@code startsAt} is the exception that proves the rule: it is the one field here that
 * does NOT go stale in a way that matters, because a show's start time does not move while
 * a request is in flight (admin writes are create-only - a show cannot be rescheduled).
 * That is why a decision can be based on it at all.
 *
 * <h2>Why the two travel together</h2>
 * The seat prices and the start time arrive in the same response, so they are returned in
 * the same object. Splitting them into two accessors on {@link com.bookmyseat.booking.client.EventClient}
 * would either fetch the whole map twice for one hold, or hide the fact that the second
 * accessor was serving a cached first call.
 *
 * <h2>What is here to be printed, not decided with</h2>
 * {@code eventId}, {@code eventTitle} and {@code venueName} are copied onto the booking row
 * so a booking can say what it is for. Nothing branches on them, and unlike
 * {@code startsAt} each MAY BE NULL: a seat map without them is still a seat map. Fail on
 * what you compute with, degrade on what you print - see {@link SeatMapResponse}.
 *
 * @param showId     the show, as event-service reported it
 * @param eventId    the event the show belongs to. Display only; may be null
 * @param eventTitle what the ticket is for. Display only; may be null
 * @param venueName  the venue's name. Display only; may be null
 * @param startsAt   when the show begins, UTC. Compared against the injected Clock to refuse
 *                   a hold on a show that has already started. Never null
 * @param seatsById  every seat in the map, keyed by show_seats id - the id a booking
 *                   references. Insertion-ordered, and that order is LOAD-BEARING: it is
 *                   the venue's own order (row, then seat number), and
 *                   {@code BookingService.hold} inserts a booking's seats in it so that
 *                   they read "C2, C5" however the client sent them
 */
public record SeatMapSnapshot(
        Long showId,
        Long eventId,
        String eventTitle,
        String venueName,
        Instant startsAt,
        Map<Long, SeatResponse> seatsById
) {
}
