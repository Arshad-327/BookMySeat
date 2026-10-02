package com.bookmyseat.booking.controller;

import com.bookmyseat.booking.MySqlContainerTest;
import com.bookmyseat.booking.client.EventClient;
import com.bookmyseat.booking.client.dto.SeatMapSnapshot;
import com.bookmyseat.booking.client.dto.SeatResponse;
import com.bookmyseat.booking.service.SeatHoldService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a booking is FOR - event, venue, start time, seat labels - copied onto the row at
 * hold time (V4) and served from there. Real MySQL and real Redis; event-service stubbed.
 *
 * <h2>What these tests pin</h2>
 * <ul>
 *   <li>hold stores the six fields and returns them;
 *   <li>they are a COPY: once stored, a different answer from event-service changes nothing,
 *       and confirm and cancel serve them without reading the seat map at all;
 *   <li>seats are stored and returned in seat-map order, not in the order they were asked
 *       for and not in id order;
 *   <li>a seat map with no title, venue or event id still produces a booking, with nulls -
 *       fail on what you compute with, degrade on what you print;
 *   <li>a booking written before V4 serialises the six fields as explicit JSON nulls, which
 *       is the case a client's "Booking #12" fallback exists for.
 * </ul>
 *
 * <p>The seat map here is built so that the three possible orders are all different:
 * request order, id order and map order. A fixture whose ids ascend in map order - which is
 * what every other hold test in this module uses - cannot tell "sorted by id" from "in
 * venue order", and would pass with either.
 */
@SpringBootTest
@AutoConfigureMockMvc
class HoldSnapshotMySqlTest extends MySqlContainerTest {

    private static final long SHOW_ID = 301L;
    private static final BigDecimal PRICE = new BigDecimal("450.00");
    private static final Instant STARTS_AT = Instant.parse("2030-01-01T18:30:00Z");

    /** Same image as docker-compose.infra.yml, thrown away with the test JVM. */
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockBean
    private SeatHoldService seatHoldService;

    @MockBean
    private EventClient eventClient;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET FOREIGN_KEY_CHECKS = 0");
                statement.execute("TRUNCATE TABLE booking_seats");
                statement.execute("TRUNCATE TABLE bookings");
                statement.execute("TRUNCATE TABLE outbox");
                statement.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
            return null;
        });
        redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });

        when(seatHoldService.holdSeats(anyLong(), anyList(), anyLong()))
                .thenReturn(new SeatHoldService.HoldResult(true, List.of()));
        when(eventClient.fetchSeatMap(anyLong()))
                .thenReturn(seatMap(42L, "Coldplay - Music of the Spheres", "Phoenix Arena"));
    }

    @Test
    @DisplayName("hold copies the event, venue, start time and seat labels onto the booking, and returns them")
    void holdStoresAndReturnsTheSnapshot() throws Exception {
        long id = bookingIdFrom(hold(UUID.randomUUID().toString(), 9005L, 9002L)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.showId").value(SHOW_ID))
                .andExpect(jsonPath("$.eventId").value(42))
                .andExpect(jsonPath("$.eventTitle").value("Coldplay - Music of the Spheres"))
                .andExpect(jsonPath("$.venueName").value("Phoenix Arena"))
                // An instant with a trailing Z, like every other time on the wire.
                .andExpect(jsonPath("$.showStartsAt").value("2030-01-01T18:30:00Z"))
                .andExpect(jsonPath("$.seats[0].rowLabel").value("A"))
                .andExpect(jsonPath("$.seats[0].seatNumber").value(1))
                .andExpect(jsonPath("$.seats[1].rowLabel").value("A"))
                .andExpect(jsonPath("$.seats[1].seatNumber").value(2)));

        // And on the row itself, by plain JDBC: a response assembled from the snapshot in
        // memory would pass everything above with nothing stored at all.
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT event_id, event_title, venue_name, show_starts_at FROM bookings WHERE id = ?", id);
        assertThat(row.get("event_id")).isEqualTo(42L);
        assertThat(row.get("event_title")).isEqualTo("Coldplay - Music of the Spheres");
        assertThat(row.get("venue_name")).isEqualTo("Phoenix Arena");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT show_starts_at = TIMESTAMP '2030-01-01 18:30:00.000000' FROM bookings WHERE id = ?",
                Boolean.class, id)).isTrue();
        assertThat(jdbcTemplate.queryForList(
                "SELECT CONCAT(row_label, seat_number) FROM booking_seats WHERE booking_id = ? ORDER BY id",
                String.class, id)).containsExactly("A1", "A2");
    }

    @Test
    @DisplayName("seats are stored and returned in seat-map order - not request order, and not id order")
    void seatsComeBackInVenueOrder() throws Exception {
        // The map runs A1 (9005), A2 (9002), B1 (9009). Asked for as 9009, 9002, 9005.
        //   request order: 9009, 9002, 9005
        //   id order:      9002, 9005, 9009
        //   map order:     9005, 9002, 9009   <- the only one that reads A1, A2, B1
        long id = bookingIdFrom(hold(UUID.randomUUID().toString(), 9009L, 9002L, 9005L)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.seats[*].showSeatId").value(contains(9005, 9002, 9009))));

        // The hold response was mapped from the entity still in memory. This read loads
        // the booking afresh, so the order now comes from the database through
        // @OrderBy("id") - and it is the same, because the rows were inserted in map order.
        mockMvc.perform(get("/api/bookings/{id}", id).header("X-User-Id", 7))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats[*].showSeatId").value(contains(9005, 9002, 9009)))
                .andExpect(jsonPath("$.seats[*].rowLabel").value(contains("A", "A", "B")))
                .andExpect(jsonPath("$.seats[*].seatNumber").value(contains(1, 2, 1)));
    }

    @Test
    @DisplayName("it is a copy: a replay after event-service renames the event still returns the title that was bought")
    void aReplayReturnsTheStoredSnapshotNotAFreshRead() throws Exception {
        String key = UUID.randomUUID().toString();
        hold(key, 9005L).andExpect(status().isCreated());

        when(eventClient.fetchSeatMap(anyLong()))
                .thenReturn(seatMap(42L, "Coldplay - RENAMED", "Somewhere Else"));

        hold(key, 9005L)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventTitle").value("Coldplay - Music of the Spheres"))
                .andExpect(jsonPath("$.venueName").value("Phoenix Arena"));
    }

    @Test
    @DisplayName("confirm and cancel serve the stored fields and never read the seat map: only hold does")
    void confirmAndCancelDoNotReadTheSeatMap() throws Exception {
        long confirmed = bookingIdFrom(hold(UUID.randomUUID().toString(), 9005L).andExpect(status().isCreated()));
        long cancelled = bookingIdFrom(hold(UUID.randomUUID().toString(), 9002L).andExpect(status().isCreated()));

        mockMvc.perform(post("/api/bookings/{id}/confirm", confirmed).header("X-User-Id", 7))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.eventTitle").value("Coldplay - Music of the Spheres"))
                .andExpect(jsonPath("$.showStartsAt").value("2030-01-01T18:30:00Z"))
                .andExpect(jsonPath("$.seats[0].rowLabel").value("A"))
                .andExpect(jsonPath("$.seats[0].seatNumber").value(1));

        mockMvc.perform(delete("/api/bookings/{id}", cancelled).header("X-User-Id", 7))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.venueName").value("Phoenix Arena"))
                .andExpect(jsonPath("$.seats[0].seatNumber").value(2));

        // Two holds, two reads of the seat map. The confirm and the cancel added none: by
        // the time they run, what they return is already on the row.
        verify(eventClient, times(2)).fetchSeatMap(anyLong());
    }

    @Test
    @DisplayName("a seat map with no title, venue or event id still produces a booking - with nulls, not a refusal")
    void aMissingHeaderDegradesToNulls() throws Exception {
        when(eventClient.fetchSeatMap(anyLong())).thenReturn(seatMap(null, null, null));

        long id = bookingIdFrom(hold(UUID.randomUUID().toString(), 9005L)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value(nullValue()))
                .andExpect(jsonPath("$.eventTitle").value(nullValue()))
                .andExpect(jsonPath("$.venueName").value(nullValue()))
                // Still present. The start time is computed with, so a seat map without one
                // never gets this far - EventClientTest pins that as a 503.
                .andExpect(jsonPath("$.showStartsAt").value("2030-01-01T18:30:00Z"))
                .andExpect(jsonPath("$.seats[0].rowLabel").value("A")));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT event_title IS NULL AND venue_name IS NULL AND event_id IS NULL FROM bookings WHERE id = ?",
                Boolean.class, id)).isTrue();
    }

    @Test
    @DisplayName("a booking held before V4 serialises all six fields as explicit nulls - the case the client's fallback is for")
    void aPreV4BookingHasNullsNotMissingKeys() throws Exception {
        // Written the way a row was written before the migration: the new columns are not
        // named, so they take their NULL default. No backfill ever fills them in.
        jdbcTemplate.update("INSERT INTO bookings (user_id, show_id, status, total_amount) "
                + "VALUES (7, 301, 'CONFIRMED', 450.00)");
        Long id = jdbcTemplate.queryForObject("SELECT MAX(id) FROM bookings", Long.class);
        jdbcTemplate.update("INSERT INTO booking_seats (booking_id, show_seat_id, sold_show_seat_id, price) "
                + "VALUES (?, 9005, 9005, 450.00)", id);

        // jsonPath(...).value(nullValue()) fails on an ABSENT key as well as passing on a
        // null one, so these assert the keys are there. A client reading
        // booking.eventTitle ?? fallback works either way; one checking
        // 'eventTitle' in booking does not, and the contract says the key is always present.
        mockMvc.perform(get("/api/bookings/{id}", id).header("X-User-Id", 7))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.eventId").value(nullValue()))
                .andExpect(jsonPath("$.eventTitle").value(nullValue()))
                .andExpect(jsonPath("$.venueName").value(nullValue()))
                .andExpect(jsonPath("$.showStartsAt").value(nullValue()))
                .andExpect(jsonPath("$.seats[0].showSeatId").value(9005))
                .andExpect(jsonPath("$.seats[0].rowLabel").value(nullValue()))
                .andExpect(jsonPath("$.seats[0].seatNumber").value(nullValue()))
                .andExpect(jsonPath("$.seats[0].price").value(450.00));
    }

    private ResultActions hold(String key, Long... seatIds) throws Exception {
        String ids = String.join(",", java.util.Arrays.stream(seatIds).map(String::valueOf).toList());
        return mockMvc.perform(post("/api/bookings/hold")
                .header("X-User-Id", 7)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"showId\":" + SHOW_ID + ",\"seatIds\":[" + ids + "]}"));
    }

    private static long bookingIdFrom(ResultActions result) {
        try {
            String body = result.andReturn().getResponse().getContentAsString();
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("id").asLong();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /**
     * A1, A2, B1 - in that order, with ids that deliberately do NOT ascend in it, so map
     * order, id order and any request order can be told apart.
     */
    private static SeatMapSnapshot seatMap(Long eventId, String eventTitle, String venueName) {
        Map<Long, SeatResponse> seats = new LinkedHashMap<>();
        seats.put(9005L, new SeatResponse(9005L, "A", 1, PRICE, SeatResponse.AVAILABLE));
        seats.put(9002L, new SeatResponse(9002L, "A", 2, PRICE, SeatResponse.AVAILABLE));
        seats.put(9009L, new SeatResponse(9009L, "B", 1, PRICE, SeatResponse.AVAILABLE));
        return new SeatMapSnapshot(SHOW_ID, eventId, eventTitle, venueName, STARTS_AT, seats);
    }
}
