package com.bookmyseat.event.mapper;

import com.bookmyseat.event.dto.request.CreateEventRequest;
import com.bookmyseat.event.dto.request.CreateShowRequest;
import com.bookmyseat.event.dto.request.CreateVenueRequest;
import com.bookmyseat.event.dto.response.EventResponse;
import com.bookmyseat.event.dto.response.ShowCreatedResponse;
import com.bookmyseat.event.entity.Event;
import com.bookmyseat.event.entity.Seat;
import com.bookmyseat.event.entity.SeatStatus;
import com.bookmyseat.event.entity.Show;
import com.bookmyseat.event.entity.ShowSeat;
import com.bookmyseat.event.entity.Venue;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;

/**
 * Hand-written static mapping for the admin write side (CLAUDE.md).
 *
 * <p>Request to entity and entity to response, both explicit. Nothing here reads a
 * lazy association it was not handed.
 */
public final class AdminMapper {

    private AdminMapper() {
    }

    public static Venue toVenue(CreateVenueRequest request) {
        Venue venue = new Venue();
        venue.setName(request.name().trim());
        venue.setCity(request.city().trim());
        venue.setAddress(request.address() == null ? null : request.address().trim());
        return venue;
    }

    public static Event toEvent(CreateEventRequest request, Venue venue) {
        Event event = new Event();
        event.setTitle(request.title().trim());
        event.setDescription(request.description());
        event.setCategory(request.category().trim());
        event.setPosterUrl(request.posterUrl());
        event.setVenue(venue);
        return event;
    }

    public static Show toShow(CreateShowRequest request, Event event) {
        Show show = new Show();
        show.setEvent(event);
        // Truncated to microseconds at assignment (CLAUDE.md Timekeeping): starts_at is
        // TIMESTAMP(6) and MySQL rounds a nanosecond Instant into it. A request body may
        // carry nine fractional digits, and untruncated the create response would return
        // them while the seat map and the event page returned the rounded column. This is
        // also the SOURCE of booking_db.bookings.show_starts_at, which is copied from what
        // this service serves - so it is the one place nanoseconds could enter that chain.
        //
        // The rule is uniform - every Instant written from Java to a TIMESTAMP(6) column -
        // and it is proven once, by HoldExpiryRoundTripMySqlTest in booking-service. This
        // write site has no round-trip test of its own, and that is not an oversight.
        show.setStartsAt(request.startsAt().truncatedTo(ChronoUnit.MICROS));
        show.setBasePrice(request.basePrice());
        return show;
    }

    /** One seat in a venue's physical map. Numbering is 1-based. */
    public static Seat toSeat(Venue venue, String rowLabel, int seatNumber, String seatType) {
        Seat seat = new Seat();
        seat.setVenue(venue);
        seat.setRowLabel(rowLabel);
        seat.setSeatNumber(seatNumber);
        seat.setSeatType(seatType);
        return seat;
    }

    /**
     * One show_seats row.
     *
     * <p>Price starts at the show's base price; status starts AVAILABLE, the only
     * state a new seat can be in. version is left null so Hibernate initialises the
     * optimistic lock counter itself rather than the column DEFAULT deciding it.
     */
    public static ShowSeat toShowSeat(Show show, Seat seat, BigDecimal basePrice) {
        ShowSeat showSeat = new ShowSeat();
        showSeat.setShow(show);
        showSeat.setSeat(seat);
        showSeat.setPrice(basePrice);
        showSeat.setStatus(SeatStatus.AVAILABLE);
        return showSeat;
    }

    public static EventResponse toEventResponse(Event event) {
        return new EventResponse(
                event.getId(),
                event.getTitle(),
                event.getDescription(),
                event.getCategory(),
                event.getPosterUrl(),
                EventMapper.toVenueResponse(event.getVenue()));
    }

    public static ShowCreatedResponse toShowCreatedResponse(Show show, int seatsCreated) {
        return new ShowCreatedResponse(
                show.getId(),
                show.getEvent().getId(),
                show.getStartsAt(),
                show.getBasePrice(),
                seatsCreated);
    }
}
