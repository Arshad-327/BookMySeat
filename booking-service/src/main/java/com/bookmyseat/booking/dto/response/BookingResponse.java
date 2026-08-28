package com.bookmyseat.booking.dto.response;

import com.bookmyseat.booking.entity.BookingStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Schema(description = "A booking")
public record BookingResponse(

        @Schema(example = "1")
        Long id,

        @Schema(example = "7")
        Long userId,

        @Schema(example = "1")
        Long showId,

        @Schema(
                description = "Always CONFIRMED today: the naive flow confirms "
                        + "immediately, with no pending or payment step.",
                example = "CONFIRMED")
        BookingStatus status,

        @Schema(example = "900.00")
        BigDecimal totalAmount,

        @Schema(description = "Null today: nothing sets a booking expiry yet", nullable = true)
        Instant expiresAt,

        @Schema(description = "UTC, trailing Z", example = "2026-08-24T15:31:46.036032Z")
        Instant createdAt,

        List<BookingSeatResponse> seats
) {
}
