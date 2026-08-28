package com.bookmyseat.booking.mapper;

import com.bookmyseat.booking.dto.response.BookingResponse;
import com.bookmyseat.booking.dto.response.BookingSeatResponse;
import com.bookmyseat.booking.entity.Booking;
import com.bookmyseat.booking.entity.BookingSeat;

/** Hand-written static mapping (CLAUDE.md). No MapStruct, no reflection. */
public final class BookingMapper {

    private BookingMapper() {
    }

    public static BookingSeatResponse toSeatResponse(BookingSeat seat) {
        return new BookingSeatResponse(seat.getShowSeatId(), seat.getPrice());
    }

    /** Requires booking.seats to be loaded; the create path holds them in memory already. */
    public static BookingResponse toResponse(Booking booking) {
        return new BookingResponse(
                booking.getId(),
                booking.getUserId(),
                booking.getShowId(),
                booking.getStatus(),
                booking.getTotalAmount(),
                booking.getExpiresAt(),
                booking.getCreatedAt(),
                booking.getSeats().stream().map(BookingMapper::toSeatResponse).toList());
    }
}
