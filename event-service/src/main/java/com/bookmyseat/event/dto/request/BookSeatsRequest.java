package com.bookmyseat.event.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "show_seats ids to mark BOOKED, and the booking they are being sold to")
public record BookSeatsRequest(

        @NotEmpty(message = "showSeatIds is required and must contain at least one id")
        @Size(max = 50, message = "showSeatIds must not exceed 50 entries")
        @Schema(example = "[9001, 9002]")
        List<@NotNull(message = "showSeatIds must not contain nulls") Long> showSeatIds,

        /*
         * REQUIRED, and a missing one is a 400 rather than a seat booked without an owner.
         *
         * A seat marked BOOKED with no booking id behind it is precisely the orphan that
         * review finding #1 is about - sold, and claimed by nobody. Accepting a null here
         * and writing the row anyway would manufacture that state from a caller's bug,
         * having just added the column that exists to detect it. So the request is
         * refused before the write path is entered at all.
         *
         * It identifies a row in booking_db.bookings. Not validated against anything -
         * this service cannot see that schema and does not try to.
         */
        @NotNull(message = "bookingId is required")
        @Schema(example = "4471", description = "booking_db.bookings id this sale belongs to")
        Long bookingId
) {
}
