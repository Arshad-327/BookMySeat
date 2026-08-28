package com.bookmyseat.event.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

@Schema(description = "One scheduled screening/performance of an event")
public record ShowResponse(

        @Schema(example = "301")
        Long id,

        @Schema(
                description = "Start time, always UTC with a trailing Z (CLAUDE.md Timekeeping)",
                example = "2026-09-14T18:30:00Z")
        Instant startsAt,

        @Schema(description = "Base price before any per-seat adjustment", example = "450.00")
        BigDecimal basePrice
) {
}
