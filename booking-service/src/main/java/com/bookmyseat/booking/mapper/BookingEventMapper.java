package com.bookmyseat.booking.mapper;

import com.bookmyseat.booking.dto.event.BookingConfirmedEvent;
import com.bookmyseat.booking.entity.Booking;
import com.bookmyseat.booking.entity.BookingSeat;

import java.time.Instant;
import java.util.UUID;

/** Hand-written mapping from a confirmed booking to its outbound event (CLAUDE.md: no MapStruct). */
public final class BookingEventMapper {

    private BookingEventMapper() {
    }

    public static BookingConfirmedEvent toConfirmedEvent(Booking booking, UUID eventId, Instant confirmedAt) {
        return new BookingConfirmedEvent(
                eventId.toString(),
                BookingConfirmedEvent.TYPE,
                booking.getId(),
                booking.getUserId(),
                booking.getShowId(),
                booking.getSeats().stream().map(BookingSeat::getShowSeatId).toList(),
                booking.getTotalAmount(),
                confirmedAt);
    }
}
