package com.bookmyseat.event.mapper;

import com.bookmyseat.event.dto.response.EventDetailResponse;
import com.bookmyseat.event.dto.response.EventSummaryResponse;
import com.bookmyseat.event.dto.response.ShowResponse;
import com.bookmyseat.event.dto.response.VenueResponse;
import com.bookmyseat.event.entity.Event;
import com.bookmyseat.event.entity.Show;
import com.bookmyseat.event.entity.Venue;

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

    /** Requires event.venue to have been fetched. */
    public static EventSummaryResponse toSummaryResponse(Event event) {
        return new EventSummaryResponse(
                event.getId(),
                event.getTitle(),
                event.getCategory(),
                event.getPosterUrl(),
                toVenueResponse(event.getVenue()));
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
