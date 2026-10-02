package com.bookmyseat.event.controller;

import com.bookmyseat.event.MySqlContainerTest;
import com.bookmyseat.event.SeatFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/events - the upcoming-show filter and the two card fields, against real MySQL.
 *
 * <h2>What this class pins</h2>
 * Three things that arrived together and depend on each other:
 *
 * <ul>
 *   <li>the list contains an event only if it has a show starting at or after now, and
 *       totalElements and totalPages count only those - the EXISTS predicate reaches the
 *       count query as well as the content query;
 *   <li>{@code nextShowStartsAt} is the earliest UPCOMING show, not the earliest show, and
 *       {@code fromPrice} is the lowest base price among upcoming shows, not among all;
 *   <li>the detail endpoint is untouched: an event the list no longer offers still resolves.
 * </ul>
 *
 * <h2>WHAT THIS CLASS DOES NOT COVER - do not read it as pinning the whole endpoint</h2>
 * Before this class existed, no test in event-service called GET /api/events at all. That
 * gap is older and wider than the change tested here, and it is still open:
 *
 * <ul>
 *   <li><b>{@code q}</b> - the contains-match on title, its case-insensitivity, and the
 *       escaping of LIKE wildcards. Untested.
 *   <li><b>{@code city} and {@code category}</b> - tested here only for one thing: that the
 *       upcoming-show filter still applies when they are present. Exact-match semantics,
 *       trimming and blank handling are untested.
 *   <li><b>Paging</b> - the default size of 20, page indexing, {@code last}, and above all
 *       the 100 cap from spring.data.web.pageable.max-page-size. Untested. {@code size=1}
 *       appears below only to make totalPages a number worth asserting.
 *   <li><b>Sorting</b> - one case only, the refusal of {@code nextShowStartsAt}. Sorting by
 *       a real Event property is untested.
 * </ul>
 *
 * <p>The clock is the real one. Past shows are in 2020 and future ones in 2029 or later,
 * so neither side is near the boundary. The inclusive edge ({@code startsAt == now}) is
 * NOT tested here; it is the same comparison {@code findUpcomingByEventId} makes.
 */
@SpringBootTest
@AutoConfigureMockMvc
class EventListEndpointMySqlTest extends MySqlContainerTest {

    private static final Instant PAST = Instant.parse("2020-01-01T18:30:00Z");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private SeatFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new SeatFixtures(context);
        fixtures.truncateAll();
    }

    @Test
    @DisplayName("an event with an upcoming show is listed, with the show's start and base price on the card")
    void upcomingEventCarriesDateAndPrice() throws Exception {
        fixtures.createShow("Phoenix Arena", 2);

        mockMvc.perform(get("/api/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Phoenix Arena event"))
                // An instant with a trailing Z, the same wire format as every other start time.
                .andExpect(jsonPath("$.content[0].nextShowStartsAt").value("2030-01-01T18:30:00Z"))
                .andExpect(jsonPath("$.content[0].fromPrice").value(450.00));
    }

    @Test
    @DisplayName("events with only past shows, or no shows, are not listed - and are not counted either")
    void eventsWithoutAnUpcomingShowAreAbsentFromContentAndCount() throws Exception {
        fixtures.createShow("Finished Hall", 1, PAST);
        fixtures.createEventWithoutShows("Unscheduled Hall");
        fixtures.createShow("Phoenix Arena", 1);

        // size=1 so the count is visible as a page count. Three events exist. A filter that
        // reached the content query and missed the count query would report totalElements 3
        // and totalPages 3 here: a pagination control offering two pages that come back empty.
        mockMvc.perform(get("/api/events").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Phoenix Arena event"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    @DisplayName("the date is the earliest UPCOMING show and the price the lowest UPCOMING base price - a past show sets neither")
    void aggregatesIgnorePastShows() throws Exception {
        // 2030-01-01 at 450.00 from the fixture, then:
        Long eventId = fixtures.eventIdOf(fixtures.createShow("Phoenix Arena", 1));
        // a past show that is both earlier and cheaper than anything upcoming,
        fixtures.addShow(eventId, PAST, new BigDecimal("100.00"));
        // the soonest upcoming show, which is the most expensive,
        fixtures.addShow(eventId, Instant.parse("2029-06-01T14:00:00Z"), new BigDecimal("600.00"));
        // and the cheapest upcoming show, which is the latest.
        fixtures.addShow(eventId, Instant.parse("2031-03-01T18:30:00Z"), new BigDecimal("300.00"));

        mockMvc.perform(get("/api/events"))
                .andExpect(status().isOk())
                // One card for four shows: the filter is EXISTS, not a join.
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(1))
                // Not 2020: the past show is earlier, and is not upcoming.
                .andExpect(jsonPath("$.content[0].nextShowStartsAt").value("2029-06-01T14:00:00Z"))
                // Not 100.00, the past show's. And not 600.00, the NEXT show's: the two
                // fields are independent aggregates and here they come from different shows.
                .andExpect(jsonPath("$.content[0].fromPrice").value(300.00));
    }

    @Test
    @DisplayName("each card gets its own event's aggregates, not its neighbour's")
    void summariesAreMatchedToTheirOwnEvent() throws Exception {
        Long first = fixtures.eventIdOf(fixtures.createShow("Phoenix Arena", 1));
        Long second = fixtures.eventIdOf(
                fixtures.createShow("Wankhede Stadium", 1, Instant.parse("2029-02-02T12:00:00Z")));
        fixtures.addShow(second, Instant.parse("2029-09-09T12:00:00Z"), new BigDecimal("120.00"));

        mockMvc.perform(get("/api/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].id").value(first))
                .andExpect(jsonPath("$.content[0].nextShowStartsAt").value("2030-01-01T18:30:00Z"))
                .andExpect(jsonPath("$.content[0].fromPrice").value(450.00))
                .andExpect(jsonPath("$.content[1].id").value(second))
                .andExpect(jsonPath("$.content[1].nextShowStartsAt").value("2029-02-02T12:00:00Z"))
                .andExpect(jsonPath("$.content[1].fromPrice").value(120.00));
    }

    @Test
    @DisplayName("the upcoming-show filter still applies alongside city and category")
    void composesWithTheOptionalFilters() throws Exception {
        Long upcomingInMumbai = fixtures.eventIdOf(fixtures.createShow("Wankhede Stadium", 1));
        Long finishedInMumbai = fixtures.eventIdOf(fixtures.createShow("Finished Hall", 1, PAST));
        fixtures.createShow("Phoenix Arena", 1);
        jdbcTemplate.update("UPDATE venues v JOIN events e ON e.venue_id = v.id SET v.city = 'Mumbai' "
                + "WHERE e.id IN (?, ?)", upcomingInMumbai, finishedInMumbai);
        jdbcTemplate.update("UPDATE events SET category = 'COMEDY' WHERE id = ?", finishedInMumbai);

        // Two events are in Mumbai; one has nothing left to book.
        mockMvc.perform(get("/api/events").param("city", "Mumbai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(upcomingInMumbai))
                .andExpect(jsonPath("$.totalElements").value(1));

        // The only COMEDY event is the finished one, so the page is empty. This is also the
        // path that skips the summary statement, and it must still answer 200.
        mockMvc.perform(get("/api/events").param("category", "COMEDY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("the detail endpoint is unchanged: an event the list does not offer still resolves, with no upcoming shows")
    void unlistedEventStillHasADetailPage() throws Exception {
        Long finished = fixtures.eventIdOf(fixtures.createShow("Finished Hall", 1, PAST));

        mockMvc.perform(get("/api/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(get("/api/events/{id}", finished))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Finished Hall event"))
                .andExpect(jsonPath("$.upcomingShows.length()").value(0));
    }

    @Test
    @DisplayName("the list cannot be sorted by next show date: ?sort=nextShowStartsAt is a 400")
    void nextShowStartsAtIsNotSortable() throws Exception {
        fixtures.createShow("Phoenix Arena", 1);

        // A recorded limitation, not an accident. The field is fetched by a statement that
        // runs after the page is sorted, so it is not an Event property and is refused like
        // any other unknown one. If "soonest first" is ever built, this test is what changes.
        mockMvc.perform(get("/api/events").param("sort", "nextShowStartsAt,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unknown sort property: nextShowStartsAt"));
    }
}
