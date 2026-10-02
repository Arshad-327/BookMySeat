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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Review finding #3, the hold half: a show that has already started cannot be held.
 *
 * <h2>What this is and is not</h2>
 * This is the FAST refusal - the one a user actually meets, before a booking row is written
 * or a Redis key is taken. It is not the guarantee. A hold taken one second before the show
 * begins is legitimate and lives for the whole TTL, so the rule has to be enforced again
 * where the seats are sold; event-service does that in its seat write, and
 * {@code StartedShowSeatWriteMySqlTest} over there pins the same boundary on that side.
 *
 * <h2>The Clock is pinned, so the boundary is a fact rather than a race</h2>
 * Fixed at {@link #NOW}, with the stubbed seat map answering one second either side of it
 * and once exactly on it. Against the real clock the third case is untestable, and it is the
 * one an off-by-one lands on.
 *
 * <p>event-service is stubbed here, deliberately: what is under test is this service's
 * decision, and the value it decides on arrives over HTTP as data. Seat holds are stubbed
 * too, so that "no hold was taken" can be asserted rather than inferred from a Redis key
 * that was never written.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StartedShowHoldMySqlTest extends MySqlContainerTest {

    private static final long SHOW_ID = 1L;
    private static final long USER_ID = 7L;
    private static final BigDecimal PRICE = new BigDecimal("450.00");

    /** Microsecond precision, which is what TIMESTAMP(6) keeps and what the comparison uses. */
    private static final Instant NOW = Instant.parse("2026-09-14T18:30:00.000000Z");

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
    private JdbcTemplate jdbcTemplate;

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
                statement.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
            return null;
        });

        when(seatHoldService.holdSeats(anyLong(), anyList(), anyLong()))
                .thenReturn(new SeatHoldService.HoldResult(true, List.of()));
    }

    @Test
    @DisplayName("a show that started one second ago is refused with 409, and nothing is written or held")
    void aShowThatHasStartedCannotBeHeld() throws Exception {
        Instant startedAt = NOW.minusSeconds(1);
        when(eventClient.fetchSeatMap(anyLong())).thenReturn(seatMap(startedAt, 1L, 2L));

        hold(UUID.randomUUID().toString(), 1L, 2L)
                .andExpect(status().isConflict())
                // The start time is in the message. "Conflict" alone is indistinguishable
                // from a seat somebody else is holding, which is a different thing to do
                // about it.
                .andExpect(jsonPath("$.message")
                        .value("Show " + SHOW_ID + " started at " + startedAt
                                + " and can no longer be booked"));

        // Refused before the insert. A 409 with a PENDING row behind it would leave the
        // sweeper to tidy up a booking that should never have existed.
        assertThat(count("SELECT COUNT(*) FROM bookings")).isZero();
        // And refused before Redis: no seat of a show nobody can book should be made
        // unavailable to anyone for the next ten minutes.
        verifyNoInteractions(seatHoldService);
    }

    @Test
    @DisplayName("a show starting in one second is still held: the refusal is about having started, not about being close")
    void aShowAboutToStartIsStillHeld() throws Exception {
        when(eventClient.fetchSeatMap(anyLong())).thenReturn(seatMap(NOW.plusSeconds(1), 1L, 2L));

        hold(UUID.randomUUID().toString(), 1L, 2L)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.showId").value(SHOW_ID));

        assertThat(count("SELECT COUNT(*) FROM bookings")).isEqualTo(1);
    }

    @Test
    @DisplayName("the boundary is INCLUSIVE: a show starting exactly now is already refused")
    void theStartInstantItselfIsRefused() throws Exception {
        // The case a real clock could never test. isAfter, not isBefore negated: at the
        // stroke of the start time the seats stop being sellable, with no grace period -
        // see ShowAlreadyStartedException.
        when(eventClient.fetchSeatMap(anyLong())).thenReturn(seatMap(NOW, 1L, 2L));

        hold(UUID.randomUUID().toString(), 1L, 2L).andExpect(status().isConflict());

        assertThat(count("SELECT COUNT(*) FROM bookings")).isZero();
    }

    private ResultActions hold(String idempotencyKey, Long... seatIds) throws Exception {
        StringBuilder seats = new StringBuilder();
        for (int i = 0; i < seatIds.length; i++) {
            seats.append(i > 0 ? "," : "").append(seatIds[i]);
        }
        return mockMvc.perform(post("/api/bookings/hold")
                .header("X-User-Id", USER_ID)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"showId\":" + SHOW_ID + ",\"seatIds\":[" + seats + "]}"));
    }

    /** The seat map as event-service would answer it, at the start time the test chooses. */
    private static SeatMapSnapshot seatMap(Instant startsAt, Long... seatIds) {
        Map<Long, SeatResponse> seats = new LinkedHashMap<>();
        for (Long seatId : seatIds) {
            seats.put(seatId, new SeatResponse(
                    seatId, "A", seatId.intValue(), PRICE, SeatResponse.AVAILABLE));
        }
        return new SeatMapSnapshot(
                SHOW_ID, 42L, "Coldplay - Music of the Spheres", "Phoenix Arena", startsAt, seats);
    }

    private int count(String sql) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
        return count == null ? 0 : count;
    }
}
