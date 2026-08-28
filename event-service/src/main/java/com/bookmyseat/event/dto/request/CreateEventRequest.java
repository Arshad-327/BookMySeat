package com.bookmyseat.event.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "Create an event at an existing venue")
public record CreateEventRequest(

        @NotBlank(message = "title is required")
        @Size(max = 200, message = "title must not exceed 200 characters")
        @Schema(example = "Coldplay - Music of the Spheres")
        String title,

        // No @Size: the column is TEXT. Capped at 65535 so an oversized body is a
        // 400 here rather than a truncation or a constraint error at the database.
        @Size(max = 65535, message = "description must not exceed 65535 characters")
        @Schema(example = "The Music of the Spheres world tour, live in India.")
        String description,

        @NotBlank(message = "category is required")
        @Size(max = 50, message = "category must not exceed 50 characters")
        @Schema(example = "CONCERT")
        String category,

        @Size(max = 500, message = "posterUrl must not exceed 500 characters")
        @Schema(example = "https://cdn.bookmyseat.local/posters/42.jpg")
        String posterUrl,

        @NotNull(message = "venueId is required")
        @Schema(example = "12")
        Long venueId
) {
}
