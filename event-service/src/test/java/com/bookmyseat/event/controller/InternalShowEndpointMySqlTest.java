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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/internal/shows/{id}?seatIds=... - the read model notification-service renders
 * a confirmation email from, against real MySQL.
 *
 * <h2>Why a read model and not the public seat map</h2>
 * The seat labels are already obtainable: GET /api/shows/{id}/seats returns every seat in
 * the show. The show's title is not - it lives on the EVENT, reachable only through
 * GET /api/events/{eventId}, and the consumer holds a show id. Building one email from
 * those two endpoints is two round trips for one message, which is the shape that becomes
 * an N+1 as soon as anything batches.
 *
 * <p>So this is a view deliberately shaped for a known caller: everything one email needs,
 * one response, nothing else. These tests pin the "nothing else" as firmly as the
 * "everything" - a read model that quietly grows into a general-purpose show endpoint has
 * lost the only property that justified adding it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InternalShowEndpointMySqlTest extends MySqlContainerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext context;

    private SeatFixtures fixtures;
    private Long showId;
    private List<Long> seatIds;

    @BeforeEach
    void setUp() {
        fixtures = new SeatFixtures(context);
        fixtures.truncateAll();
        showId = fixtures.createShow("DY Patil Stadium", 4);
        seatIds = fixtures.showSeatIds(showId);
    }

    @Test
    @DisplayName("one call returns the title, venue, start time and the labels for exactly the ids asked for")
    void returnsEverythingOneEmailNeeds() throws Exception {
        mockMvc.perform(get("/api/internal/shows/{id}", showId)
                        .param("seatIds", seatIds.get(1) + "," + seatIds.get(2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.showId").value(showId))
                .andExpect(jsonPath("$.eventTitle").value("DY Patil Stadium event"))
                .andExpect(jsonPath("$.venueName").value("DY Patil Stadium"))
                // CLAUDE.md Timekeeping: an Instant, serialised with a trailing Z. The email
                // renders it in Asia/Kolkata; the wire stays UTC.
                .andExpect(jsonPath("$.startsAt").value("2030-01-01T18:30:00Z"))
                .andExpect(jsonPath("$.seats.length()").value(2))
                .andExpect(jsonPath("$.seats[0].id").value(seatIds.get(1)))
                .andExpect(jsonPath("$.seats[0].label").value("A2"))
                .andExpect(jsonPath("$.seats[1].id").value(seatIds.get(2)))
                .andExpect(jsonPath("$.seats[1].label").value("A3"));
    }

    @Test
    @DisplayName("returns ONLY the requested seats - not the whole seat map")
    void returnsOnlyTheRequestedSeats() throws Exception {
        mockMvc.perform(get("/api/internal/shows/{id}", showId)
                        .param("seatIds", String.valueOf(seatIds.get(0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats.length()").value(1))
                .andExpect(jsonPath("$.seats[0].label").value("A1"));
    }

    @Test
    @DisplayName("discloses no price and no seat status - an email needs neither")
    void disclosesNothingBeyondWhatTheEmailRenders() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/internal/shows/{id}", showId)
                        .param("seatIds", String.valueOf(seatIds.get(0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats[0].price").doesNotExist())
                .andExpect(jsonPath("$.seats[0].status").doesNotExist())
                .andExpect(jsonPath("$.basePrice").doesNotExist())
                .andExpect(jsonPath("$.upcomingShows").doesNotExist())
                .andReturn();

        // The exact body, so the read model cannot grow a field without this test being read.
        assertThat(result.getResponse().getContentAsString()).isEqualTo(
                "{\"showId\":" + showId + ",\"eventTitle\":\"DY Patil Stadium event\","
                        + "\"venueName\":\"DY Patil Stadium\",\"startsAt\":\"2030-01-01T18:30:00Z\","
                        + "\"seats\":[{\"id\":" + seatIds.get(0) + ",\"label\":\"A1\"}]}");
    }

    @Test
    @DisplayName("a seat id belonging to ANOTHER show is silently not returned, never leaked across shows")
    void seatsFromAnotherShowAreNotReturned() throws Exception {
        Long otherShowId = fixtures.createShow("Somewhere Else", 2);
        Long foreignSeatId = fixtures.showSeatIds(otherShowId).get(0);

        mockMvc.perform(get("/api/internal/shows/{id}", showId)
                        .param("seatIds", seatIds.get(0) + "," + foreignSeatId))
                .andExpect(status().isOk())
                // Not an error: this is a best-effort read for a cosmetic purpose, and one
                // unrecognised id must not cost the caller the labels it CAN have. But the
                // foreign seat is absent, so the query's show scoping is doing its job.
                .andExpect(jsonPath("$.seats.length()").value(1))
                .andExpect(jsonPath("$.seats[0].id").value(seatIds.get(0)));
    }

    @Test
    @DisplayName("an unknown show is 404, in the standard error shape")
    void unknownShowIsNotFound() throws Exception {
        mockMvc.perform(get("/api/internal/shows/{id}", 999_999L).param("seatIds", "1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.path").value("/api/internal/shows/999999"));
    }

    @Test
    @DisplayName("seatIds is optional: the show's details come back with an empty seat list")
    void seatIdsIsOptional() throws Exception {
        // notification-service always sends ids, but an absent parameter must not be a 400.
        // The show details alone are still a better email than no email.
        mockMvc.perform(get("/api/internal/shows/{id}", showId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventTitle").value("DY Patil Stadium event"))
                .andExpect(jsonPath("$.seats.length()").value(0));
    }
}
