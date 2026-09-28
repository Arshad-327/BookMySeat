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

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/shows/{id}/seats - the public seat map, against real MySQL.
 *
 * <h2>What these tests are for</h2>
 * The map grew a header - eventTitle, venueName, startsAt - and the service grew a second
 * query to load it, reusing {@code findWithEventAndVenueById} from the internal read model.
 * Two things had to be pinned as a result:
 *
 * <ul>
 *   <li>the three fields are actually served, and {@code startsAt} is a UTC instant with a
 *       trailing Z rather than a local date-time (CLAUDE.md Timekeeping). booking-service
 *       parses this value to refuse a hold on a show that has already started, so the
 *       format is a contract, not a display choice;
 *   <li>both 404s survive. The unknown-show 404 now comes from the show lookup rather than
 *       from an empty seat list, and the seatless-show 404 still comes from the seat list.
 *       They are one status code with two causes, and the move could have dropped either.
 * </ul>
 *
 * <p>No assertion here counts the response's keys. A client pinning the exact JSON would
 * have failed when this header was added, and it should not - the map is a display
 * response that is expected to grow. {@code InternalShowEndpointMySqlTest} is the one that
 * asserts an exact body, deliberately, because that read model must NOT grow.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SeatMapEndpointMySqlTest extends MySqlContainerTest {

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
    @DisplayName("the map says which show it is: event title, venue name and the start time as a UTC instant")
    void carriesTheShowHeader() throws Exception {
        mockMvc.perform(get("/api/shows/{id}/seats", showId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.showId").value(showId))
                .andExpect(jsonPath("$.eventTitle").value("DY Patil Stadium event"))
                .andExpect(jsonPath("$.venueName").value("DY Patil Stadium"))
                // The exact wire format, not merely "a start time". A LocalDateTime would
                // serialise as 2030-01-01T18:30:00 with no zone and read as host-local
                // wherever it landed - the failure CLAUDE.md Timekeeping exists to prevent.
                .andExpect(jsonPath("$.startsAt").value("2030-01-01T18:30:00Z"));
    }

    @Test
    @DisplayName("the grid itself is unchanged: counts, rows and show_seats ids")
    void stillServesTheGrid() throws Exception {
        mockMvc.perform(get("/api/shows/{id}/seats", showId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSeats").value(4))
                .andExpect(jsonPath("$.availableSeats").value(4))
                // One row: the fixture puts all four seats in row A.
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].rowLabel").value("A"))
                .andExpect(jsonPath("$.rows[0].seats.length()").value(4))
                // The show_seats id, which is what a booking references - not the venue seat id.
                .andExpect(jsonPath("$.rows[0].seats[0].id").value(seatIds.get(0)))
                .andExpect(jsonPath("$.rows[0].seats[0].status").value("AVAILABLE"));
    }

    @Test
    @DisplayName("a BOOKED seat still shows as BOOKED and is out of availableSeats")
    void countsReflectABookedSeat() throws Exception {
        fixtures.bookDirectly(seatIds.get(0), 4471L);

        mockMvc.perform(get("/api/shows/{id}/seats", showId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSeats").value(4))
                .andExpect(jsonPath("$.availableSeats").value(3))
                .andExpect(jsonPath("$.rows[0].seats[0].status").value("BOOKED"));
    }

    @Test
    @DisplayName("an unknown show id is 404 - now from the show lookup, before the seat map is read")
    void unknownShowIsNotFound() throws Exception {
        mockMvc.perform(get("/api/shows/{id}/seats", showId + 999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Show " + (showId + 999) + " not found"));
    }

    @Test
    @DisplayName("a show that EXISTS with no seats is still 404, not an empty grid")
    void seatlessShowIsAlsoNotFound() throws Exception {
        // Unreachable through the admin API - AdminShowService refuses a venue with no
        // seats, and AdminShowServiceTest pins that. Built directly here because this 404
        // is the second half of a status code with two causes, and the only way to know the
        // emptiness check still fires is to hand it an empty show.
        Long seatless = fixtures.createShow("Seatless Hall", 0);

        mockMvc.perform(get("/api/shows/{id}/seats", seatless))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Show " + seatless + " not found"));
    }
}
