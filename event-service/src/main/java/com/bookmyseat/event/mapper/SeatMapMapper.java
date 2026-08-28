package com.bookmyseat.event.mapper;

import com.bookmyseat.event.dto.response.SeatMapResponse;
import com.bookmyseat.event.dto.response.SeatResponse;
import com.bookmyseat.event.dto.response.SeatRowResponse;
import com.bookmyseat.event.entity.Seat;
import com.bookmyseat.event.entity.SeatStatus;
import com.bookmyseat.event.entity.ShowSeat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hand-written static mapping (CLAUDE.md).
 *
 * <p>Groups an already-sorted flat list into rows. Reads only ShowSeat and its
 * fetched Seat - never seat.getVenue() or showSeat.getShow(), both of which are
 * lazy and would each cost one extra SELECT per seat.
 */
public final class SeatMapMapper {

    private SeatMapMapper() {
    }

    public static SeatMapResponse toSeatMapResponse(Long showId, List<ShowSeat> showSeats) {
        // Linked, not Hash: the query already ordered by row label, and this
        // preserves that order instead of returning rows in hash order.
        Map<String, List<SeatResponse>> byRow = new LinkedHashMap<>();
        int available = 0;

        for (ShowSeat showSeat : showSeats) {
            Seat seat = showSeat.getSeat();
            byRow.computeIfAbsent(seat.getRowLabel(), key -> new ArrayList<>())
                    .add(toSeatResponse(showSeat, seat));
            if (showSeat.getStatus() == SeatStatus.AVAILABLE) {
                available++;
            }
        }

        List<SeatRowResponse> rows = byRow.entrySet().stream()
                .map(entry -> new SeatRowResponse(entry.getKey(), entry.getValue()))
                .toList();

        return new SeatMapResponse(showId, showSeats.size(), available, rows);
    }

    /** id is the show_seats id: the row a booking actually targets. */
    private static SeatResponse toSeatResponse(ShowSeat showSeat, Seat seat) {
        return new SeatResponse(
                showSeat.getId(),
                seat.getRowLabel(),
                seat.getSeatNumber(),
                showSeat.getPrice(),
                showSeat.getStatus());
    }
}
