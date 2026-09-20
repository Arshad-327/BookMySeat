package com.bookmyseat.event.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Body for the seat release. Deliberately a separate type from
 * {@link BookSeatsRequest}, for the same reason the endpoint is a separate endpoint:
 * the two run in opposite directions under opposite guards, and a shared type is the
 * first step towards a shared method with a mode flag on it.
 */
@Schema(description = "show_seats ids to release, and the booking releasing them")
public record ReleaseSeatsRequest(

        @NotEmpty(message = "showSeatIds is required and must contain at least one id")
        @Size(max = 50, message = "showSeatIds must not exceed 50 entries")
        @Schema(example = "[9001, 9002]")
        List<@NotNull(message = "showSeatIds must not contain nulls") Long> showSeatIds,

        /*
         * REQUIRED. A null is a 400 and never "release it anyway" - see the WHERE clause
         * comment in InternalSeatService.release. Without an owner to match, this endpoint
         * frees seats it has no claim to.
         */
        @NotNull(message = "bookingId is required")
        @Schema(example = "4471", description = "booking_db.bookings id that holds these seats")
        Long bookingId
) {
}
