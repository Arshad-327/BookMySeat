package com.bookmyseat.booking.client.dto;

import java.util.List;

/** Body for POST /api/internal/shows/{showId}/seats/book. */
public record BookSeatsRequest(List<Long> showSeatIds) {
}
