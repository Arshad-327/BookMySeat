package com.bookmyseat.booking.mapper;

import com.bookmyseat.booking.dto.response.BookingResponse;
import com.bookmyseat.booking.dto.response.BookingSeatResponse;
import com.bookmyseat.booking.entity.Booking;
import com.bookmyseat.booking.entity.BookingSeat;

import java.util.List;

/** Hand-written static mapping (CLAUDE.md). No MapStruct, no reflection. */
public final class BookingMapper {

    private BookingMapper() {
    }

    public static BookingSeatResponse toSeatResponse(BookingSeat seat) {
        return new BookingSeatResponse(
                seat.getShowSeatId(),
                seat.getRowLabel(),
                seat.getSeatNumber(),
                seat.getPrice());
    }

    /** Requires booking.seats to be loaded; the create path holds them in memory already. */
    public static BookingResponse toResponse(Booking booking) {
        return toResponse(booking, booking.getSeats());
    }

    /**
     * The same, with the seats handed over instead of read off the booking.
     *
     * <p>For the list, which fetches the seats of a whole page in one statement. This must
     * NOT touch {@code booking.getSeats()}: it is a lazy collection, and reading it here
     * would fire one SELECT per booking - the N+1 the caller's query exists to avoid.
     *
     * @param seats this booking's seats, already in the order they should be served
     */
    public static BookingResponse toResponse(Booking booking, List<BookingSeat> seats) {
        return new BookingResponse(
                booking.getId(),
                booking.getUserId(),
                booking.getShowId(),
                booking.getEventId(),
                booking.getEventTitle(),
                booking.getVenueName(),
                booking.getShowStartsAt(),
                booking.getStatus(),
                booking.getTotalAmount(),
                booking.getExpiresAt(),
                booking.getCreatedAt(),
                seats.stream().map(BookingMapper::toSeatResponse).toList());
    }
}
