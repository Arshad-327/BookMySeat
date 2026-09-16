package com.bookmyseat.event.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One seat, reduced to what a confirmation email prints: which seat was asked about, and
 * what to call it.
 *
 * <p>Not {@link SeatResponse}, which also carries rowLabel, seatNumber, price and status.
 * The label is pre-joined here on purpose - the caller renders "Seats: C2, C3" and has no
 * use for the parts, and giving it the parts would mean two services agreeing on how to
 * join them.
 *
 * @param id    the show_seats id, echoed so the caller can match a label to the id it sent
 * @param label the seat as a human reads it, row label and seat number joined, e.g. "C2"
 */
@Schema(description = "One seat's printable label, for a confirmation message")
public record InternalSeatLabelResponse(

        @Schema(example = "9001")
        Long id,

        @Schema(example = "C2")
        String label
) {
}
