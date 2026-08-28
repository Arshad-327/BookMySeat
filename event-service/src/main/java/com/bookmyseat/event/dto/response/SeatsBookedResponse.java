package com.bookmyseat.event.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Outcome of a blind seat-booking update")
public record SeatsBookedResponse(

        @Schema(example = "301")
        Long showId,

        @Schema(description = "Ids the caller asked to book", example = "2")
        int requested,

        @Schema(
                description = "Rows the UPDATE actually changed. May be lower than "
                        + "requested: an id that does not exist, belongs to another show, "
                        + "or was already BOOKED changes nothing. The caller is NOT told "
                        + "which - see the controller notes.",
                example = "2")
        int updated
) {
}
