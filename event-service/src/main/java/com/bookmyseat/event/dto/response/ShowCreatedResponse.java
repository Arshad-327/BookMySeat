package com.bookmyseat.event.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

@Schema(description = "A created show, with the size of its seat fan-out")
public record ShowCreatedResponse(

        @Schema(example = "301")
        Long id,

        @Schema(example = "42")
        Long eventId,

        @Schema(description = "UTC, trailing Z", example = "2026-09-14T18:30:00Z")
        Instant startsAt,

        @Schema(example = "450.00")
        BigDecimal basePrice,

        @Schema(
                description = "show_seats rows created, one per seat in the venue. "
                        + "Always at least 1: a show cannot be created at a venue with no seats.",
                example = "60")
        int seatsCreated
) {
}
