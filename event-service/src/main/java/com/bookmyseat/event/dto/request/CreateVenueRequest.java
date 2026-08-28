package com.bookmyseat.event.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Sizes mirror the columns in V1__initial_schema.sql exactly. */
@Schema(description = "Create a venue")
public record CreateVenueRequest(

        @NotBlank(message = "name is required")
        @Size(max = 160, message = "name must not exceed 160 characters")
        @Schema(example = "Phoenix Arena")
        String name,

        @NotBlank(message = "city is required")
        @Size(max = 80, message = "city must not exceed 80 characters")
        @Schema(example = "Bengaluru")
        String city,

        @Size(max = 255, message = "address must not exceed 255 characters")
        @Schema(example = "42 MG Road, Bengaluru 560001")
        String address
) {
}
