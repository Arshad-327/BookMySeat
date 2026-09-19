package com.bookmyseat.booking.controller;

import com.bookmyseat.booking.MySqlContainerTest;
import com.bookmyseat.booking.client.EventClient;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Idempotency through the real hold endpoint, both layers, real MySQL and real Redis.
 *
 * <p>Redis is a real container rather than a mock because the third test is literally
 * "after Redis is flushed": it needs a store that can genuinely lose a key and then be
 * observed not to have it. A mocked fast path would only prove that a stub returned
 * what the test told it to.
 *
 * <p>Seat holds and event-service are stubbed to say yes to everything - this is about
 * creation being replay-safe, not about seat contention, which
 * {@code ConfirmSoldSeatMySqlTest} and the k6 harness cover.
 */
@SpringBootTest
@AutoConfigureMockMvc
class IdempotentHoldMySqlTest extends MySqlContainerTest {

    private static final long SHOW_ID = 1L;
    private static final BigDecimal PRICE = new BigDecimal("450.00");

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
                statement.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
            return null;
        });
        flushRedis();

        when(seatHoldService.holdSeats(anyLong(), anyList(), anyLong()))
                .thenReturn(new SeatHoldService.HoldResult(true, List.of()));
        when(eventClient.fetchSeatsById(anyLong())).thenReturn(seatMap(1L, 2L, 3L, 4L));
    }

    @Test
    @DisplayName("the same key twice returns the same booking id and creates exactly one row")
    void sameKeyTwiceCreatesOneBooking() throws Exception {
        String key = UUID.randomUUID().toString();

        long first = bookingIdFrom(hold(7L, key, 1L, 2L).andExpect(status().isCreated()));

        // The replay is 200, not 201: nothing was created the second time.
        long second = bookingIdFrom(hold(7L, key, 1L, 2L).andExpect(status().isOk()));

        assertThat(second).isEqualTo(first);
        assertThat(count("SELECT COUNT(*) FROM bookings")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM booking_seats")).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM bookings WHERE idempotency_key = '" + key + "'")).isEqualTo(1);
    }

    @Test
    @DisplayName("two different keys create two bookings")
    void differentKeysCreateTwoBookings() throws Exception {
        long first = bookingIdFrom(
                hold(7L, UUID.randomUUID().toString(), 1L, 2L).andExpect(status().isCreated()));
        long second = bookingIdFrom(
                hold(7L, UUID.randomUUID().toString(), 3L, 4L).andExpect(status().isCreated()));

        assertThat(second).isNotEqualTo(first);
        assertThat(count("SELECT COUNT(*) FROM bookings")).isEqualTo(2);
    }

    @Test
    @DisplayName("a key reused after Redis is flushed still resolves to the original booking, via the constraint")
    void keyReusedAfterRedisFlushResolvesThroughTheConstraint() throws Exception {
        String key = UUID.randomUUID().toString();
        long first = bookingIdFrom(hold(7L, key, 1L, 2L).andExpect(status().isCreated()));
        assertThat(redisTemplate.opsForValue().get("idem:hold:" + key)).isEqualTo(String.valueOf(first));

        // Evict the fast path. Everything the service knows about this key is now in
        // MySQL, in bookings.idempotency_key and its unique index.
        flushRedis();
        assertThat(redisTemplate.opsForValue().get("idem:hold:" + key)).isNull();

        // Falls through to the insert, the index refuses it, and the recovery path
        // returns the original - 200, not the 409 the constraint would otherwise give,
        // and certainly not a 500.
        long replay = bookingIdFrom(hold(7L, key, 1L, 2L).andExpect(status().isOk()));

        assertThat(replay).isEqualTo(first);
        assertThat(count("SELECT COUNT(*) FROM bookings")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM booking_seats")).isEqualTo(2);

        // The recovery repopulated the fast path, so the NEXT replay does not pay for
        // another failed insert to learn the same answer.
        assertThat(redisTemplate.opsForValue().get("idem:hold:" + key)).isEqualTo(String.valueOf(first));
    }

    @Test
    @DisplayName("another user presenting someone else's key is refused, not served the booking")
    void keyBelongingToAnotherUserIsRefused() throws Exception {
        String key = UUID.randomUUID().toString();
        hold(7L, key, 1L, 2L).andExpect(status().isCreated());

        hold(8L, key, 1L, 2L)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Idempotency-Key " + key
                        + " was already used by a different user"));

        assertThat(count("SELECT COUNT(*) FROM bookings")).isEqualTo(1);
    }

    @Test
    @DisplayName("a missing or malformed Idempotency-Key is a 400, and creates nothing")
    void badKeyIsRejected() throws Exception {
        mockMvc.perform(post("/api/bookings/hold")
                        .header("X-User-Id", 7L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Required header 'Idempotency-Key' is missing"));

        hold(7L, "not-a-uuid", 1L)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Idempotency-Key must be a UUID, got: not-a-uuid"));

        assertThat(count("SELECT COUNT(*) FROM bookings")).isZero();
    }

    private ResultActions hold(Long userId, String idempotencyKey, Long... seatIds) throws Exception {
        return mockMvc.perform(post("/api/bookings/hold")
                .header("X-User-Id", userId)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(seatIds)));
    }

    private static String body(Long... seatIds) {
        StringBuilder seats = new StringBuilder();
        for (int i = 0; i < seatIds.length; i++) {
            seats.append(i > 0 ? "," : "").append(seatIds[i]);
        }
        return "{\"showId\":" + SHOW_ID + ",\"seatIds\":[" + seats + "]}";
    }

    private static long bookingIdFrom(ResultActions result) throws Exception {
        return Long.parseLong(com.jayway.jsonpath.JsonPath
                .read(result.andReturn().getResponse().getContentAsString(), "$.id").toString());
    }

    private static Map<Long, SeatResponse> seatMap(Long... seatIds) {
        Map<Long, SeatResponse> seats = new LinkedHashMap<>();
        for (Long seatId : seatIds) {
            seats.put(seatId, new SeatResponse(
                    seatId, "A", seatId.intValue(), PRICE, SeatResponse.AVAILABLE));
        }
        return seats;
    }

    /**
     * A real FLUSHALL against the real container - the eviction the third test is about.
     *
     * <p>Run through redis-cli inside the container rather than through the template, so
     * the test is not asking the same client under test to forget something.
     */
    private static void flushRedis() {
        try {
            REDIS.execInContainer("redis-cli", "FLUSHALL");
        } catch (IOException ex) {
            throw new UncheckedIOException("could not flush the test Redis", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted flushing the test Redis", ex);
        }
    }

    private long count(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class);
    }
}
