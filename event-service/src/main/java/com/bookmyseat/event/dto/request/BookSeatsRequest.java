package com.bookmyseat.event.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

@Schema(description = "show_seats ids to mark BOOKED")
public record BookSeatsRequest(

        @NotEmpty(message = "showSeatIds is required and must contain at least one id")
        @Size(max = 50, message = "showSeatIds must not exceed 50 entries")
        @Schema(example = "[9001, 9002]")
        List<@NotNull(message = "showSeatIds must not contain nulls") Long> showSeatIds
) {
}
