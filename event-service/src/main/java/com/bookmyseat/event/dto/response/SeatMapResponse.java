package com.bookmyseat.event.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "The full seat map for one show, grouped by row")
public record SeatMapResponse(

        @Schema(example = "301")
        Long showId,

        @Schema(description = "Every seat in the map, booked included", example = "120")
        int totalSeats,

        @Schema(
                description = "Seats with status AVAILABLE. Counted from the same snapshot, "
                        + "so it can be stale the moment it is read - it is a display hint, "
                        + "never a reservation.",
                example = "97")
        int availableSeats,

        @Schema(description = "Rows ordered by row label")
        List<SeatRowResponse> rows
) {
}
