package com.bookmyseat.booking.client.dto;

import java.util.List;

/**
 * Mirror of event-service's seat map response.
 *
 * <p>Duplicated rather than shared: a common DTO module would couple the two
 * services' deploy cycles, and CLAUDE.md keeps services independent. Unknown
 * fields are ignored by Jackson, so event-service can add fields without breaking
 * this.
 */
public record SeatMapResponse(
        Long showId,
        int totalSeats,
        int availableSeats,
        List<SeatRowResponse> rows
) {
}
