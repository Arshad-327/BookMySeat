package com.bookmyseat.event.mapper;

import com.bookmyseat.event.dto.response.EventDetailResponse;
import com.bookmyseat.event.dto.response.EventSummaryResponse;
import com.bookmyseat.event.dto.response.ShowResponse;
import com.bookmyseat.event.dto.response.VenueResponse;
import com.bookmyseat.event.entity.Event;
import com.bookmyseat.event.entity.Show;
import com.bookmyseat.event.entity.Venue;
import com.bookmyseat.event.repository.UpcomingShowSummary;

import java.util.List;

/**
 * Hand-written static mapping (CLAUDE.md). No MapStruct, no reflection.
 *
 * <p>Every method here assumes the associations it reads were already fetched by
 * the repository query. Reading a lazy proxy in a mapper is how N+1 starts.
 */
public final class EventMapper {

    private EventMapper() {
    }

    public static VenueResponse toVenueResponse(Venue venue) {
        return new VenueResponse(
                venue.getId(),
                venue.getName(),
                venue.getCity(),
                venue.getAddress());
    }

    public static ShowResponse toShowResponse(Show show) {
        return new ShowResponse(
                show.getId(),
                show.getStartsAt(),
                show.getBasePrice());
    }

    /**
     * Requires event.venue to have been fetched.
     *
     * @param upcoming this event's row from {@code findUpcomingSummariesByEventIds}. Passed
     *                 in, not navigated to: the event has no shows collection, and the
     *                 caller fetched every summary for the page in one statement
     */
    public static EventSummaryResponse toSummaryResponse(Event event, UpcomingShowSummary upcoming) {
        return new EventSummaryResponse(
                event.getId(),
                event.getTitle(),
                event.getCategory(),
                event.getPosterUrl(),
                toVenueResponse(event.getVenue()),
                upcoming.nextShowStartsAt(),
                upcoming.fromPrice());
    }

    /** Requires event.venue to have been fetched; shows are passed in, not navigated. */
    public static EventDetailResponse toDetailResponse(Event event, List<Show> upcomingShows) {
        return new EventDetailResponse(
                event.getId(),
                event.getTitle(),
                event.getDescription(),
                event.getCategory(),
                event.getPosterUrl(),
                toVenueResponse(event.getVenue()),
                upcomingShows.stream().map(EventMapper::toShowResponse).toList());
    }
}
