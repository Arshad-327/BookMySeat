package com.bookmyseat.event.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "An event as it appears in a list. No shows: use GET /api/events/{id} for those.")
public record EventSummaryResponse(

        @Schema(example = "42")
        Long id,

        @Schema(example = "Coldplay - Music of the Spheres")
        String title,

        @Schema(example = "CONCERT")
        String category,

        @Schema(example = "https://cdn.bookmyseat.local/posters/42.jpg")
        String posterUrl,

        VenueResponse venue
) {
}
