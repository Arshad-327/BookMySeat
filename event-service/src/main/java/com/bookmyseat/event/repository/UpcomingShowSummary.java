package com.bookmyseat.event.repository;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What an event card says about an event's upcoming shows: when the next one starts and
 * the lowest base price among them. One row per event, from
 * {@link ShowRepository#findUpcomingSummariesByEventIds}.
 *
 * <p>A query result, not an entity and not a response: it never leaves the service layer.
 *
 * @param eventId          the event the two aggregates belong to
 * @param nextShowStartsAt MIN(starts_at) over the event's upcoming shows
 * @param fromPrice        MIN(base_price) over the same shows. The lowest BASE price of an
 *                         upcoming show - see {@code EventSummaryResponse.fromPrice} for why
 *                         that is not the same claim as "the cheapest seat"
 */
public record UpcomingShowSummary(Long eventId, Instant nextShowStartsAt, BigDecimal fromPrice) {
}
