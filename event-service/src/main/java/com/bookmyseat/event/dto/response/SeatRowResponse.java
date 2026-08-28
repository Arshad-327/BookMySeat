package com.bookmyseat.event.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "One row of the seat map, seats ordered by seat number")
public record SeatRowResponse(

        @Schema(example = "F")
        String rowLabel,

        List<SeatResponse> seats
) {
}
