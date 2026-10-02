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
import com.bookmyseat.event.repository.UpcomingShowSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;
    private final ShowRepository showRepository;

    /** Injected so "upcoming" can be pinned in a test (CLAUDE.md Timekeeping). */
    private final Clock clock;

    /**
     * Filtered, paged list of events that have an upcoming show, each with the start of
     * its next show and its lowest base price.
     *
     * <p>Three statements, by design and not by accident: Spring Data issues the
     * content SELECT and a separate COUNT to populate totalElements, and one grouped
     * statement over shows then fetches the date and price for every event on the page
     * at once. All three are bounded - none grows with anything but the page, which is
     * capped at 100 - so this is not N+1. The venue of every row arrives in the content
     * query via the fetch in EventSpecifications. An empty page skips the third.
     *
     * <h2>One {@code now}, read once</h2>
     * The EXISTS filter and the summary statement both ask "which shows are upcoming", and
     * they must mean the same instant by it. Read twice, the two could straddle a show
     * starting: the filter would list an event for a show the summary then considers past,
     * and the event would have no date. So it is read here and handed to both.
     *
     * <h2>Why the date and price are a separate statement at all</h2>
     * The list is a Specification returning Event entities, so it has nowhere to select an
     * extra column, and a mapped formula cannot take a bind parameter - it would have to
     * compare against SQL NOW(), which CLAUDE.md forbids. The cost of doing it this way is
     * that <b>the list cannot be sorted by next show date</b>: sorting is applied to Event
     * properties in the first statement, which has never heard of the second.
     * {@code ?sort=nextShowStartsAt} is a 400 like any other unknown property.
     */
    @Transactional(readOnly = true)
    public PageResponse<EventSummaryResponse> findEvents(
            String city, String category, String q, Pageable pageable) {
        Instant now = Instant.now(clock);
        Page<Event> page = eventRepository.findAll(
                EventSpecifications.filter(city, category, q, now), pageable);

        Map<Long, UpcomingShowSummary> summaries = page.isEmpty()
                ? Map.of()
                : showRepository.findUpcomingSummariesByEventIds(
                                page.getContent().stream().map(Event::getId).toList(), now)
                        .stream()
                        .collect(Collectors.toMap(UpcomingShowSummary::eventId, Function.identity()));

        return PageResponse.from(page,
                event -> EventMapper.toSummaryResponse(event, summaryFor(event, summaries, now)));
    }

    /**
     * The summary for a listed event, which must exist.
     *
     * <p>THROWS rather than returning null, and the throw is doing a job. A listed event
     * passed the EXISTS filter, so it had a show with startsAt >= now; the summary
     * statement selects on the same condition with the same {@code now}; and both run in
     * this method's one read-only transaction. A missing row therefore means one of those
     * three things is not true - most plausibly that the statements did not see one
     * snapshot of {@code shows}, which is assumed here and has not been separately
     * verified. This guard is that verification: silent while the assumption holds, and a
     * stack trace instead of a blank date on a card when it does not.
     */
    private static UpcomingShowSummary summaryFor(
            Event event, Map<Long, UpcomingShowSummary> summaries, Instant now) {
        UpcomingShowSummary summary = summaries.get(event.getId());
        if (summary == null) {
            throw new IllegalStateException("event " + event.getId()
                    + " was listed by the EXISTS filter in EventSpecifications but had no row in "
                    + "the summary query (ShowRepository.findUpcomingSummariesByEventIds); both "
                    + "use the same :now (" + now + ") and run in one read-only transaction, so "
                    + "this means that assumption is wrong");
        }
        return summary;
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
