package com.bookmyseat.booking.client.dto;

import java.util.List;

/**
 * Body for POST /api/internal/shows/{showId}/seats/release.
 *
 * <p>{@code bookingId} is what event-service matches against each seat's recorded owner.
 * It is not a formality: without it that endpoint would free whatever is in the seat at
 * the moment the call lands, including a seat a later booking has since bought.
 */
public record ReleaseSeatsRequest(List<Long> showSeatIds, Long bookingId) {
}
