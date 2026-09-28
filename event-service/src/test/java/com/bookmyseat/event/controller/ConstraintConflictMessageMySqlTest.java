package com.bookmyseat.event.controller;

import com.bookmyseat.event.MySqlContainerTest;
import com.bookmyseat.event.SeatFixtures;
import com.bookmyseat.event.repository.SeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The 409 a unique constraint produces says WHICH rule was hit.
 *
 * <h2>Why this exists</h2>
 * This service used to answer "The request conflicts with existing data" for every constraint
 * violation, while booking-service chose its message from the constraint name. Two services
 * disagreeing about one status code; the naming side won, and this pins the result.
 *
 * <p>The constraint NAME is not in the response and must not get there - it goes to the log.
 * What the caller receives is a hand-written sentence per rule, which is why these assertions
 * are on exact strings rather than on a substring of a constraint name.
 *
 * <h2>Not a two-thread race, and that is deliberate</h2>
 * The same arrangement as auth-service's duplicate-email test. A real race would have to get
 * two seat-generation calls past {@code existsByVenueId} before either commits, with nowhere to
 * synchronise them, so it would usually be refused by the pre-check and prove nothing. Here the
 * pre-check is stubbed to miss - exactly what the loser of the race sees - and everything after
 * it is real: a real {@code uq_seats_venue_row_number} index refuses the insert, and a real
 * {@code DataIntegrityViolationException} reaches the handler.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConstraintConflictMessageMySqlTest extends MySqlContainerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Spied, not mocked: the insert has to be the real one, because MySQL is what refuses it. */
    @SpyBean
    private SeatRepository seatRepository;

    private Long venueId;

    @BeforeEach
    void setUp() {
        // Truncate through the shared fixture, so this test empties event_db the same way
        // every other real-MySQL test in this service does. The venue itself is created
        // through the admin API in each test, not by SQL: the id has to come back from the
        // endpoint anyway, and going through it keeps the seed on the same code path as the
        // call under test.
        new SeatFixtures(context).truncateAll();
    }

    @Test
    @DisplayName("the pre-check answers 409 on an ordinary second seat generation")
    void thePreCheckAnswersFirst() throws Exception {
        createVenue();

        generateSeats().andExpect(status().isCreated());

        // Nothing stubbed: the common path, one indexed SELECT rather than a failed insert.
        generateSeats()
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Venue " + venueId + " already has seats; generating again would duplicate them"));
    }

    @Test
    @DisplayName("a generation that gets PAST the pre-check names the same rule, not a generic conflict")
    void theUniqueIndexNamesTheRule() throws Exception {
        createVenue();
        generateSeats().andExpect(status().isCreated());
        int seatsAfterFirst = countSeats();

        // The race, made deterministic.
        doReturn(false).when(seatRepository).existsByVenueId(anyLong());

        generateSeats()
                .andExpect(status().isConflict())
                // The rule, in words. Before this change it was "The request conflicts with
                // existing data" - true, and useless to whoever is reading it.
                .andExpect(jsonPath("$.message").value(
                        "This venue already has seats; generating again would duplicate them"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                // The constraint name stays in the log, never in the body.
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("uq_"))));

        // Rolled back: the refused insert added nothing.
        assertThat(countSeats()).isEqualTo(seatsAfterFirst);
    }

    private void createVenue() throws Exception {
        String body = mockMvc.perform(post("/api/admin/venues")
                        .header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Phoenix Arena\",\"city\":\"Bengaluru\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        venueId = Long.valueOf(com.jayway.jsonpath.JsonPath.read(body, "$.id").toString());
    }

    private ResultActions generateSeats() throws Exception {
        return mockMvc.perform(post("/api/admin/venues/{id}/seats/generate", venueId)
                .header("X-User-Role", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rows\":[\"A\",\"B\"],\"seatsPerRow\":3,\"seatType\":\"REGULAR\"}"));
    }

    /** Plain JDBC, so no persistence context can mask what MySQL holds. */
    private int countSeats() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM seats WHERE venue_id = ?", Integer.class, venueId);
        return count == null ? 0 : count;
    }
}
