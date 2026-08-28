package com.bookmyseat.event.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

@Schema(description = "Create a show for an event. Fans the venue's seats out into show_seats.")
public record CreateShowRequest(

        @NotNull(message = "eventId is required")
        @Schema(example = "42")
        Long eventId,

        /*
         * Instant, never LocalDateTime (CLAUDE.md Timekeeping). Jackson parses an
         * ISO-8601 instant; a value without a zone is rejected rather than silently
         * read in the host zone.
         *
         * Deliberately not @Future: that constraint reads the system default clock,
         * and CLAUDE.md requires every time-based decision to go through the
         * injected Clock. Back-dating a show is also legitimate when loading
         * historical data, so it is not validated at all here.
         */
        @NotNull(message = "startsAt is required")
        @Schema(description = "UTC instant, ISO-8601 with a trailing Z", example = "2026-09-14T18:30:00Z")
        Instant startsAt,

        @NotNull(message = "basePrice is required")
        @DecimalMin(value = "0.00", message = "basePrice must not be negative")
        // The column is DECIMAL(10,2): 8 integer digits and 2 fractional.
        @DecimalMax(value = "99999999.99", message = "basePrice must not exceed 99999999.99")
        @Digits(integer = 8, fraction = 2, message = "basePrice must have at most 2 decimal places")
        @Schema(example = "450.00")
        BigDecimal basePrice
) {
}
