package com.bookmyseat.booking.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/**
 * One seat within a booking.
 *
 * <p>{@code rowLabel} and {@code seatNumber} are served as two parts, the way
 * event-service's seat map serves them, so a client chooses how to join them ("C14",
 * "Row C, Seat 14"). Both are null on a booking held before they existed - see
 * {@link BookingResponse} - and {@code showSeatId} is then all there is to show.
 */
@Schema(description = "One seat within a booking")
public record BookingSeatResponse(

        @Schema(description = "The show_seats id in event_db", example = "9001")
        Long showSeatId,

        @Schema(description = "The seat's row as it was labelled when held. Null on a booking "
                + "held before this field existed.",
                nullable = true, example = "C")
        String rowLabel,

        @Schema(description = "The seat's number within its row. Null whenever rowLabel is.",
                nullable = true, example = "14")
        Integer seatNumber,

        @Schema(example = "450.00")
        BigDecimal price
) {
}
