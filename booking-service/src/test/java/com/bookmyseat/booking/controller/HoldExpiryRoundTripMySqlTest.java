package com.bookmyseat.booking.controller;

import com.bookmyseat.booking.MySqlContainerTest;
import com.bookmyseat.booking.client.EventClient;
import com.bookmyseat.booking.client.dto.SeatMapSnapshot;
import com.bookmyseat.booking.client.dto.SeatResponse;
import com.bookmyseat.booking.service.SeatHoldService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The expiresAt a hold RETURNS is the expiresAt a later read returns. Real MySQL.
 *
 * <h2>The defect this exists for</h2>
 * {@code java.time.Instant} carries nanoseconds and {@code TIMESTAMP(6)} holds microseconds.
 * The hold response is mapped from the entity still in memory, so it served the Instant as
 * computed - nine fractional digits on a clock that has them - while the column kept six.
 * Every later read of the same booking then returned a different expiresAt, with nothing
 * failing anywhere: a client holding the first value and comparing it to a refetch saw a
 * mismatch it could not explain. Same class as a signing algorithm or a column mapping that
 * two sides quietly disagree about.
 *
 * <h2>Why the Clock is pinned, and to this value</h2>
 * The real clock would make this test pass or fail by platform: Windows ticks in 100ns
 * units, so the defect shows; a clock that happens to tick in whole microseconds hides it.
 * The pinned instant has nine significant fractional digits, so the mismatch is certain
 * wherever this runs.
 *
 * <p>It ends in 789 nanoseconds on purpose. The value the database keeps for an untruncated
 * write is not even a prefix of what was sent: it is ROUNDED, to .123457, not cut to
 * .123456. So "the client could just compare the first six digits" does not rescue the old
 * behaviour, and this test would catch a fix that rounded in Java to match as readily as
 * one that did nothing - the contract is that the two values are equal, however that is
 * achieved, and truncating at assignment is the way that cannot drift.
 */
@SpringBootTest
@AutoConfigureMockMvc
class HoldExpiryRoundTripMySqlTest extends MySqlContainerTest {

    private static final long SHOW_ID = 301L;

    /** Nine significant fractional digits. See the class javadoc. */
    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00.123456789Z");

    @TestConfiguration
    static class NanosecondClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

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

    @Autowired
    private ObjectMapper objectMapper;

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
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });

        Map<Long, SeatResponse> seats = new LinkedHashMap<>();
        seats.put(9001L, new SeatResponse(9001L, "A", 1, new BigDecimal("450.00"), SeatResponse.AVAILABLE));
        when(eventClient.fetchSeatMap(anyLong())).thenReturn(new SeatMapSnapshot(
                SHOW_ID, 42L, "Coldplay - Music of the Spheres", "Phoenix Arena",
                Instant.parse("2030-01-01T18:30:00Z"), seats));
        when(seatHoldService.holdSeats(anyLong(), anyList(), anyLong()))
                .thenReturn(new SeatHoldService.HoldResult(true, List.of()));
    }

    @Test
    @DisplayName("the expiresAt a hold returns equals the expiresAt every later read returns")
    void expiresAtSurvivesTheRoundTrip() throws Exception {
        JsonNode held = objectMapper.readTree(mockMvc.perform(post("/api/bookings/hold")
                        .header("X-User-Id", 7)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"showId\":" + SHOW_ID + ",\"seatIds\":[9001]}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        long id = held.get("id").asLong();
        String fromHold = held.get("expiresAt").asText();

        // What a client gets when it refetches the booking: mapped from a freshly loaded
        // row, so this is the database's value.
        String fromRead = objectMapper.readTree(mockMvc.perform(get("/api/bookings/{id}", id)
                        .header("X-User-Id", 7))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("expiresAt").asText();

        // And the column itself, as MySQL prints it - no driver, no Jackson in between.
        String inColumn = jdbcTemplate.queryForObject(
                "SELECT DATE_FORMAT(expires_at, '%Y-%m-%dT%H:%i:%s.%f') FROM bookings WHERE id = ?",
                String.class, id);

        assertThat(fromHold)
                .as("hold returned %s, a re-read returned %s, and the column holds %s",
                        fromHold, fromRead, inColumn)
                .isEqualTo(fromRead);
        // Pinned exactly, so the value is the TRUNCATED one and the test says which: ten
        // minutes after NOW, cut to microseconds. A fix that made the two equal by some
        // other route would have to change this line and explain itself.
        assertThat(fromHold).isEqualTo("2026-10-02T10:10:00.123456Z");
        assertThat(inColumn).isEqualTo("2026-10-02T10:10:00.123456");
    }
}
