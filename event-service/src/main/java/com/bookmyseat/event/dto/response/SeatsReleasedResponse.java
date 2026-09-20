package com.bookmyseat.event.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Outcome of releasing seats back to AVAILABLE")
public record SeatsReleasedResponse(

        @Schema(example = "301")
        Long showId,

        @Schema(description = "Distinct ids the caller asked to release", example = "2")
        int requested,

        @Schema(
                description = "How many seats this call actually freed. Unlike the booking "
                        + "call, this is a genuine count of rows changed and is routinely "
                        + "LESS than requested - zero, most of the time. A seat this booking "
                        + "does not own is skipped, not refused, so a smaller number here is "
                        + "the normal outcome and never an error.",
                example = "2")
        int released
) {
}
