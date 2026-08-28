package com.bookmyseat.event.dto.response;

import com.bookmyseat.event.entity.SeatStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "One seat, priced and statused for a specific show")
public record SeatResponse(

        @Schema(
                description = "The show_seats id - the id to send when booking. "
                        + "This is NOT the venue-level seat id.",
                example = "9001")
        Long id,

        @Schema(example = "F")
        String rowLabel,

        @Schema(example = "12")
        Integer seatNumber,

        @Schema(description = "Price for this seat at this show", example = "450.00")
        BigDecimal price,

        @Schema(
                description = "AVAILABLE or BOOKED, and nothing else. A seat currently "
                        + "held by another user in checkout still reads AVAILABLE: holds "
                        + "live only as Redis keys with a TTL.",
                example = "AVAILABLE")
        SeatStatus status
) {
}
