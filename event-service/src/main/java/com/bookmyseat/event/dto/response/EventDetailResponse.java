package com.bookmyseat.event.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "A single event with its venue and its upcoming shows")
public record EventDetailResponse(

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

        VenueResponse venue,

        @Schema(description = "Shows starting at or after the current instant, earliest first. "
                + "Past shows are never returned.")
        List<ShowResponse> upcomingShows
) {
}
