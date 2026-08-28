package com.bookmyseat.booking.client.dto;

import java.math.BigDecimal;

/**
 * One seat as event-service reports it.
 *
 * <p>id is the show_seats id. status is AVAILABLE or BOOKED and nothing else -
 * a seat being held by another user in checkout still reads AVAILABLE, because
 * holds live only as Redis keys. Kept as a String rather than an enum so an
 * unrecognised value from a newer event-service does not fail deserialisation.
 */
public record SeatResponse(
        Long id,
        String rowLabel,
        Integer seatNumber,
        BigDecimal price,
        String status
) {
    public static final String AVAILABLE = "AVAILABLE";

    public boolean isAvailable() {
        return AVAILABLE.equalsIgnoreCase(status);
    }
}
