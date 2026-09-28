package com.bookmyseat.event.controller;

import com.bookmyseat.event.MySqlContainerTest;
import com.bookmyseat.event.SeatFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Review finding #3: seats cannot be sold for a show that has already started.
 *
 * <h2>Why the boundary is tested here, on the write</h2>
 * This is the moment a seat is sold and the one step of a confirm that cannot be rolled
 * back. booking-service also refuses a HOLD on a started show, which is the fast, precise
 * refusal a user normally meets - but a hold taken one second before the show begins lives
 * for another ten minutes, and every confirm inside that window would have been allowed if
 * this check did not exist. The hold check is the courtesy; this one is the rule.
 *
 * <h2>The Clock is pinned, so the boundary is a fact rather than a race</h2>
 * Fixed at {@link #NOW}, with shows built one second either side of it and one exactly on
 * it. Against the real clock those three cases would be "past", "future" and untestable,
 * and the off-by-one worth guarding against is precisely the third. CLAUDE.md Timekeeping is
 * what makes this possible: the comparison reads the injected Clock, never SQL NOW().
 */
@SpringBootTest
@AutoConfigureMockMvc
class StartedShowSeatWriteMySqlTest extends MySqlContainerTest {

    private static final long BOOKING_ID = 4471L;

    /** Microsecond precision, which is what TIMESTAMP(6) keeps and what the comparison uses. */
    private static final Instant NOW = Instant.parse("2026-09-14T18:30:00.000000Z");

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext context;

    private SeatFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new SeatFixtures(context);
        fixtures.truncateAll();
    }

    @Test
    @DisplayName("a show that started one second ago is refused with 409, and no seat is written")
    void aShowThatHasStartedCannotBeSold() throws Exception {
        Instant startedAt = NOW.minusSeconds(1);
        Long showId = fixtures.createShow("Last Night Arena", 2, startedAt);
        List<Long> seats = fixtures.showSeatIds(showId);

        book(showId, seats)
                .andExpect(status().isConflict())
                // The start time is in the message: a caller told only "conflict" cannot tell
                // this apart from a seat somebody else has already bought.
                .andExpect(jsonPath("$.message").value(containsString("started at " + startedAt)))
                .andExpect(jsonPath("$.message").value(containsString("Show " + showId)));

        // A refusal that wrote something is not a refusal. The version is unmoved too, so no
        // UPDATE reached the row at all.
        for (Long seat : seats) {
            assertThat(fixtures.row(seat).status()).isEqualTo("AVAILABLE");
            assertThat(fixtures.row(seat).bookedByBookingId()).isNull();
            assertThat(fixtures.row(seat).version()).isZero();
        }
    }

    @Test
    @DisplayName("a show starting in one second is still sold: the refusal is about having started, not about being close")
    void aShowAboutToStartIsStillSold() throws Exception {
        Long showId = fixtures.createShow("One Second Out", 2, NOW.plusSeconds(1));
        List<Long> seats = fixtures.showSeatIds(showId);

        book(showId, seats)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(2))
                .andExpect(jsonPath("$.updated").value(2));

        for (Long seat : seats) {
            assertThat(fixtures.row(seat).status()).isEqualTo("BOOKED");
            assertThat(fixtures.row(seat).bookedByBookingId()).isEqualTo(BOOKING_ID);
        }
    }

    @Test
    @DisplayName("the boundary is INCLUSIVE: a show starting exactly now is already refused")
    void theStartInstantItselfIsRefused() throws Exception {
        // The one case a real clock could never test, and the one an off-by-one lands on.
        // isAfter, not isBefore negated: at the stroke of the start time the seats stop being
        // sellable. There is no grace period, deliberately - see ShowAlreadyStartedException.
        Long showId = fixtures.createShow("On The Stroke", 2, NOW);
        List<Long> seats = fixtures.showSeatIds(showId);

        book(showId, seats).andExpect(status().isConflict());

        assertThat(fixtures.row(seats.get(0)).status()).isEqualTo("AVAILABLE");
        assertThat(fixtures.row(seats.get(0)).version()).isZero();
    }

    @Test
    @DisplayName("a started show's seats can still be RELEASED - the guard is on selling only")
    void aStartedShowsSeatsCanStillBeReleased() throws Exception {
        Long showId = fixtures.createShow("Last Night Arena", 2, NOW.minusSeconds(1));
        List<Long> seats = fixtures.showSeatIds(showId);
        // An orphan: BOOKED to a booking that never committed. That is the state the sweeper
        // and cancel exist to clean up, and a hold taken before the show began routinely
        // expires after it - so release must not inherit the booking guard. If it ever does,
        // this row becomes permanently unfreeable.
        fixtures.bookDirectly(seats.get(0), BOOKING_ID);

        mockMvc.perform(post("/api/internal/shows/{showId}/seats/release", showId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(List.of(seats.get(0)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released").value(1));

        assertThat(fixtures.row(seats.get(0)).status()).isEqualTo("AVAILABLE");
        assertThat(fixtures.row(seats.get(0)).bookedByBookingId()).isNull();
    }

    private ResultActions book(Long showId, List<Long> showSeatIds) throws Exception {
        return mockMvc.perform(post("/api/internal/shows/{showId}/seats/book", showId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(showSeatIds)));
    }

    private static String body(List<Long> showSeatIds) {
        String ids = showSeatIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        return "{\"showSeatIds\":[" + ids + "],\"bookingId\":" + BOOKING_ID + "}";
    }
}
