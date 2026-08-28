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
                description = "PENDING after /hold, CONFIRMED after /confirm.",
                example = "PENDING")
        BookingStatus status,

        @Schema(example = "900.00")
        BigDecimal totalAmount,

        @Schema(
                description = "When the seat holds lapse, while PENDING. Null once "
                        + "CONFIRMED - a confirmed booking does not expire.",
                nullable = true,
                example = "2026-08-28T17:14:42.113204Z")
        Instant expiresAt,

        @Schema(description = "UTC, trailing Z", example = "2026-08-24T15:31:46.036032Z")
        Instant createdAt,

        List<BookingSeatResponse> seats
) {
}
