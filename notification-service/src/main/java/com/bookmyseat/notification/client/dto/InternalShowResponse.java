package com.bookmyseat.notification.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

/** event-service's GET /api/internal/shows/{id}?seatIds=... response. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InternalShowResponse(
        Long showId,
        String eventTitle,
        String venueName,
        Instant startsAt,
        List<InternalSeatLabelResponse> seats) {
}
