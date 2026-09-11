package com.bookmyseat.event.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Outcome of marking seats BOOKED")
public record SeatsBookedResponse(

        @Schema(example = "301")
        Long showId,

        @Schema(description = "Distinct ids the caller asked to book", example = "2")
        int requested,

        @Schema(
                description = "Rows changed to BOOKED. Always equal to requested: a request "
                        + "that cannot book every seat fails with 404 or 409 instead of "
                        + "returning a smaller number.",
                example = "2")
        int updated
) {
}
