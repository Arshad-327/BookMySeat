package com.bookmyseat.booking.client.dto;

import java.util.List;

/**
 * Body for POST /api/internal/shows/{showId}/seats/book.
 *
 * <p>{@code bookingId} is required on the far side: event-service answers 400 rather
 * than marking a seat BOOKED with nobody behind it. Both fields must be populated -
 * this record is the wire contract, not a convenience.
 */
public record BookSeatsRequest(List<Long> showSeatIds, Long bookingId) {
}
