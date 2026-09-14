package com.bookmyseat.booking.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "Seats to hold for a show (up to 10)")
public record CreateBookingRequest(

        @NotNull(message = "showId is required")
        @Schema(example = "1")
        Long showId,

        @NotEmpty(message = "seatIds is required and must contain at least one seat")
        @Size(max = 10, message = "seatIds must not exceed 10 seats per booking")
        @Schema(
                description = "show_seats ids, as returned by GET /api/shows/{id}/seats",
                example = "[1, 2]")
        List<@NotNull(message = "seatIds must not contain nulls") Long> seatIds
) {
}
