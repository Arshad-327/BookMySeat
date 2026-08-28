package com.bookmyseat.event.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The venue an event is staged at")
public record VenueResponse(

        @Schema(example = "12")
        Long id,

        @Schema(example = "Phoenix Arena")
        String name,

        @Schema(example = "Bengaluru")
        String city,

        @Schema(example = "42 MG Road, Bengaluru 560001")
        String address
) {
}
