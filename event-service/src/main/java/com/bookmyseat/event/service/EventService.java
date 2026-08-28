package com.bookmyseat.event.service;

import com.bookmyseat.event.dto.response.EventDetailResponse;
import com.bookmyseat.event.dto.response.EventSummaryResponse;
import com.bookmyseat.event.dto.response.PageResponse;
import com.bookmyseat.event.entity.Event;
import com.bookmyseat.event.entity.Show;
import com.bookmyseat.event.exception.EventNotFoundException;
import com.bookmyseat.event.mapper.EventMapper;
import com.bookmyseat.event.repository.EventRepository;
import com.bookmyseat.event.repository.EventSpecifications;
import com.bookmyseat.event.repository.ShowRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;
    private final ShowRepository showRepository;

    /** Injected so "upcoming" can be pinned in a test (CLAUDE.md Timekeeping). */
    private final Clock clock;

    /**
     * Filtered, paged event list.
     *
     * <p>Two statements, by design and not by accident: Spring Data issues the
     * content SELECT and a separate COUNT to populate totalElements. Both are
     * bounded - neither grows with the number of rows returned - so this is not
     * N+1. The venue of every row arrives in the content query via the fetch in
     * EventSpecifications.
     */
    @Transactional(readOnly = true)
    public PageResponse<EventSummaryResponse> findEvents(
            String city, String category, String q, Pageable pageable) {

        Page<Event> page = eventRepository.findAll(
                EventSpecifications.filter(city, category, q), pageable);

        return PageResponse.from(page, EventMapper::toSummaryResponse);
    }

    /**
     * One event, its venue, and its future shows.
     *
     * <p>Two statements: event+venue, then the shows. The show list is fetched by
     * its own query rather than mapped as a OneToMany collection so that the
     * "upcoming" filter runs in SQL - loading every show ever scheduled just to
     * discard the past ones in Java would get worse with every completed show.
     */
    @Transactional(readOnly = true)
    public EventDetailResponse findEventById(Long id) {
        Event event = eventRepository.findByIdWithVenue(id)
                .orElseThrow(() -> new EventNotFoundException(id));

        Instant now = Instant.now(clock);
        List<Show> upcomingShows = showRepository.findUpcomingByEventId(id, now);

        return EventMapper.toDetailResponse(event, upcomingShows);
    }
}
