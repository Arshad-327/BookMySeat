package com.bookmyseat.booking.client.dto;

import java.util.List;

public record SeatRowResponse(String rowLabel, List<SeatResponse> seats) {
}
