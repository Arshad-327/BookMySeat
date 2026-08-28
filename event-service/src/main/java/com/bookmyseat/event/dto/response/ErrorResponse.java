package com.bookmyseat.event.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * The single error shape for this service, matching auth-service field for field.
 *
 * timestamp is an Instant (CLAUDE.md Timekeeping), so it serialises with a
 * trailing Z and is unambiguous to any client.
 */
@Schema(description = "Standard error response")
public record ErrorResponse(

        @Schema(example = "2026-08-24T15:31:46.036032Z")
        Instant timestamp,

        @Schema(example = "404")
        int status,

        @Schema(example = "Not Found")
        String error,

        @Schema(example = "Event 42 not found")
        String message,

        @Schema(example = "/api/events/42")
        String path
) {
}
