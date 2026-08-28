package com.bookmyseat.event.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Outcome of generating a venue's seat map")
public record SeatGenerationResponse(

        @Schema(example = "12")
        Long venueId,

        @Schema(description = "Row labels created, in request order", example = "[\"A\",\"B\",\"C\"]")
        List<String> rows,

        @Schema(example = "10")
        int seatsPerRow,

        @Schema(description = "rows.size() * seatsPerRow", example = "30")
        int seatsCreated
) {
}
