package com.bookmyseat.event.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Generate a venue's physical seat map: every row gets the same number of seats.
 *
 * <p>Seat numbers run 1..seatsPerRow. Total seats created is rows.size() * seatsPerRow,
 * and the request is capped so one call cannot ask for an unbounded insert.
 */
@Schema(description = "Generate the physical seats for a venue")
public record GenerateSeatsRequest(

        @NotEmpty(message = "rows is required and must contain at least one row label")
        @Size(max = 200, message = "rows must not exceed 200 entries")
        @Schema(
                description = "Row labels. Each must fit the row_label column, VARCHAR(4). "
                        + "Duplicates within one request are rejected.",
                example = "[\"A\", \"B\", \"C\"]")
        List<
                @NotNull(message = "row labels must not be null")
                @Pattern(regexp = "^[A-Za-z0-9]{1,4}$",
                        message = "each row label must be 1-4 letters or digits")
                String> rows,

        @NotNull(message = "seatsPerRow is required")
        @Min(value = 1, message = "seatsPerRow must be at least 1")
        @Max(value = 500, message = "seatsPerRow must not exceed 500")
        @Schema(example = "10")
        Integer seatsPerRow,

        @NotNull(message = "seatType is required")
        @Pattern(regexp = "^[A-Za-z0-9_]{1,20}$",
                message = "seatType must be 1-20 letters, digits or underscores")
        @Schema(example = "REGULAR")
        String seatType
) {
}
