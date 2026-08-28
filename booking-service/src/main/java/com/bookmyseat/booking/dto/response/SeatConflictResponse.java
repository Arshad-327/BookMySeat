package com.bookmyseat.booking.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * The 409 body when a hold is refused because other bookings already hold seats.
 *
 * <p>Field for field the same as {@link ErrorResponse}, plus
 * {@code conflictingSeatIds}. Keeping the five standard fields means a client
 * with generic error handling still works; adding the list means a client that
 * cares can grey out exactly those seats and let the user pick again, instead of
 * parsing ids back out of an English sentence.
 *
 * <p>The list is the complete set, not the first conflict found: hold_seats.lua
 * tries every requested seat before rolling back, so a user booking four seats
 * learns about all the taken ones in one response.
 */
@Schema(description = "409 returned when one or more seats are already held")
public record SeatConflictResponse(

        @Schema(example = "2026-08-28T17:04:42.113204Z")
        Instant timestamp,

        @Schema(example = "409")
        int status,

        @Schema(example = "Conflict")
        String error,

        @Schema(example = "Seats are currently held by another booking: [1, 7]")
        String message,

        @Schema(example = "/api/bookings/hold")
        String path,

        @Schema(
                description = "show_seats ids held by a different booking. Nothing in "
                        + "this request was held - the hold is all or nothing.",
                example = "[1, 7]")
        List<Long> conflictingSeatIds
) {
}
