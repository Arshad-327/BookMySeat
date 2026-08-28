package com.bookmyseat.event.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "An event as returned by the admin create endpoint")
public record EventResponse(

        @Schema(example = "42")
        Long id,

        @Schema(example = "Coldplay - Music of the Spheres")
        String title,

        @Schema(example = "The Music of the Spheres world tour, live in India.")
        String description,

        @Schema(example = "CONCERT")
        String category,

        @Schema(example = "https://cdn.bookmyseat.local/posters/42.jpg")
        String posterUrl,

        VenueResponse venue
) {
}
