package com.bookmyseat.booking.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "One seat within a booking")
public record BookingSeatResponse(

        @Schema(description = "The show_seats id in event_db", example = "1")
        Long showSeatId,

        @Schema(example = "450.00")
        BigDecimal price
) {
}
