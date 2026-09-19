package com.bookmyseat.booking.controller;

import com.bookmyseat.booking.MySqlContainerTest;
import com.bookmyseat.booking.client.EventClient;
import com.bookmyseat.booking.client.dto.SeatResponse;
import com.bookmyseat.booking.client.dto.SeatsBookedResponse;
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
import org.springframework.test.web.servlet.MvcResult;
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
 * P4.10 reproduction of review finding #2 (docs/review-2026-09-18.md), kept as its
 * regression guard now that P4.11 has fixed it.
 *
 * <p>hold and confirm used to share one Redis namespace, {@code idem:{key} -> bookingId}.
 * IdempotentBookingService#confirm consults it before doing anything, so a key that
 * hold already wrote resolved on the fast path and confirm returned that booking
 * without ever calling BookingService#confirm - and without reading the {id} path
 * variable at all. The namespaces are now {@code idem:hold:{key}} and
 * {@code idem:confirm:{key}}, so neither can answer for the other.
 *
 * <p>Real MySQL and real Redis, deliberately. The bug IS the Redis fast path, so a
 * mocked IdempotencyService would only prove that a stub returned what the test told
 * it to. The assertion reads the booking back out of MySQL rather than off the
 * response body, because the response body is exactly the thing that lies here: it
 * is a 200 carrying a booking, which looks like success.
 *
 * <p>Seat holds and event-service are stubbed to say yes to everything. Nothing here
 * is about seat contention; every layer in front of the bug is made to cooperate so
 * that the only reason the booking is not CONFIRMED is the one under test.
 *
 * <p><b>Both tests failed before the fix and pass after it.</b> Neither assertion was
 * touched in the fixing commit; the only edit was the Redis key literal below, which
 * reads hold's bookkeeping under its new name.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConfirmKeyReuseMySqlTest extends MySqlContainerTest {

    private static final long SHOW_ID = 1L;
    private static final long USER_ID = 7L;
    private static final BigDecimal PRICE = new BigDecimal("450.00");

    /** An id no booking in this test can have: the table is truncated before each test. */
    private static final long ABSENT_BOOKING_ID = 99L;

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
        flushRedis();

        // Every layer in front of the bug says yes.
        when(seatHoldService.holdSeats(anyLong(), anyList(), anyLong()))
                .thenReturn(new SeatHoldService.HoldResult(true, List.of()));
        when(seatHoldService.seatsNotHeldBy(anyLong(), anyList(), anyLong())).thenReturn(List.of());
        when(eventClient.fetchSeatsById(anyLong())).thenReturn(seatMap(1L, 2L, 3L, 4L));
        when(eventClient.markSeatsBooked(anyLong(), anyList())).thenAnswer(invocation -> {
            List<?> ids = invocation.getArgument(1);
            return new SeatsBookedResponse(invocation.getArgument(0), ids.size(), ids.size());
        });
    }

    @Test
    @DisplayName("FINDING #2: confirm carrying the hold's own Idempotency-Key leaves the booking PENDING")
    void confirmWithTheHoldsKeyDoesNotConfirm() throws Exception {
        String key = UUID.randomUUID().toString();

        long bookingId = bookingIdFrom(hold(key, 1L, 2L).andExpect(status().isCreated()));
        assertThat(statusOf(bookingId)).isEqualTo("PENDING");
        // The fast-path entry hold left behind. This is what confirm is about to read.
        assertThat(redisTemplate.opsForValue().get("idem:hold:" + key))
                .isEqualTo(String.valueOf(bookingId));

        MvcResult confirmResult = confirm(bookingId, key).andReturn();

        // Read the truth back out of MySQL, not off the response body. The body is a 200
        // carrying a booking either way; only the row says whether anything happened.
        assertThat(statusOf(bookingId))
                .as("booking %d after POST /api/bookings/%d/confirm returned HTTP %d with body %s",
                        bookingId, bookingId, confirmResult.getResponse().getStatus(),
                        confirmResult.getResponse().getContentAsString())
                .isEqualTo("CONFIRMED");

        // And the two things a real confirm must also leave behind.
        assertThat(count("SELECT COUNT(*) FROM booking_seats WHERE sold_show_seat_id IS NOT NULL"))
                .as("booking_seats rows marked sold")
                .isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM outbox"))
                .as("outbox rows written by the confirm")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("FINDING #2: confirming an id that does not exist returns a different booking entirely")
    void confirmIgnoresThePathVariableOnACacheHit() throws Exception {
        String key = UUID.randomUUID().toString();

        long bookingId = bookingIdFrom(hold(key, 1L, 2L).andExpect(status().isCreated()));
        assertThat(bookingId).isNotEqualTo(ABSENT_BOOKING_ID);
        assertThat(count("SELECT COUNT(*) FROM bookings WHERE id = " + ABSENT_BOOKING_ID)).isZero();

        // Confirm an id that is not in the table, carrying a key that resolves to one that is.
        MvcResult result = confirm(ABSENT_BOOKING_ID, key).andReturn();
        String body = result.getResponse().getContentAsString();

        assertThat(result.getResponse().getStatus())
                .as("POST /api/bookings/%d/confirm with a key resolving to booking %d returned body %s",
                        ABSENT_BOOKING_ID, bookingId, body)
                .isEqualTo(404);
    }

    @Test
    @DisplayName("P4.11 guard: one key for the whole checkout - hold then confirm - confirms the booking")
    void oneKeyAcrossHoldAndConfirmCompletesTheCheckout() throws Exception {
        // The exact sequence a React checkout will send: it mints one key for the
        // purchase and puts it on both calls. This overlaps
        // confirmWithTheHoldsKeyDoesNotConfirm on purpose - that one is the finding,
        // read from the database; this one is the client flow, read from the wire, and
        // is meant to keep working even if the finding's test is ever re-scoped.
        String key = UUID.randomUUID().toString();

        long bookingId = bookingIdFrom(hold(key, 1L, 2L).andExpect(status().isCreated()));

        confirm(bookingId, key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(bookingId))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        assertThat(statusOf(bookingId)).isEqualTo("CONFIRMED");
        assertThat(count("SELECT COUNT(*) FROM booking_seats WHERE sold_show_seat_id IS NOT NULL"))
                .as("both seats marked sold")
                .isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM outbox"))
                .as("exactly one booking.confirmed event")
                .isEqualTo(1);

        // The two keys are now independent records naming the same booking, which is
        // what makes the sequence legal rather than lucky.
        assertThat(redisTemplate.opsForValue().get("idem:hold:" + key))
                .isEqualTo(String.valueOf(bookingId));
        assertThat(redisTemplate.opsForValue().get("idem:confirm:" + key))
                .isEqualTo(String.valueOf(bookingId));
    }

    @Test
    @DisplayName("P4.11: a genuine confirm replay - same key, same id - returns the booking, not 409")
    void replayingAConfirmKeyAgainstTheSameBookingReturnsTheBooking() throws Exception {
        long bookingId = bookingIdFrom(
                hold(UUID.randomUUID().toString(), 1L, 2L).andExpect(status().isCreated()));
        String confirmKey = UUID.randomUUID().toString();

        confirm(bookingId, confirmKey).andExpect(status().isOk());

        // Without the key this second call is the 409 a non-PENDING booking draws. The
        // key is the whole reason it is not, and that must survive the namespacing.
        confirm(bookingId, confirmKey)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(bookingId))
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        assertThat(count("SELECT COUNT(*) FROM outbox"))
                .as("the replay must not write a second event")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("P4.11: one confirm key pointed at a second booking is 409, and confirms nothing")
    void aConfirmKeyReusedForAnotherBookingIsRefused() throws Exception {
        long first = bookingIdFrom(
                hold(UUID.randomUUID().toString(), 1L, 2L).andExpect(status().isCreated()));
        long second = bookingIdFrom(
                hold(UUID.randomUUID().toString(), 3L, 4L).andExpect(status().isCreated()));
        String confirmKey = UUID.randomUUID().toString();

        confirm(first, confirmKey).andExpect(status().isOk());

        // Same key, different booking. Not a replay of anything - the caller has a bug -
        // and refused the way a key belonging to another user is refused.
        confirm(second, confirmKey)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Idempotency-Key " + confirmKey
                        + " was already used to confirm booking " + first
                        + ", not booking " + second));

        assertThat(statusOf(second))
                .as("the refused confirm must not have run")
                .isEqualTo("PENDING");
        assertThat(count("SELECT COUNT(*) FROM outbox"))
                .as("only the first booking's event")
                .isEqualTo(1);
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

    private ResultActions confirm(long bookingId, String idempotencyKey) throws Exception {
        return mockMvc.perform(post("/api/bookings/{id}/confirm", bookingId)
                .header("X-User-Id", USER_ID)
                .header("Idempotency-Key", idempotencyKey));
    }

    private static long bookingIdFrom(ResultActions result) throws Exception {
        return Long.parseLong(com.jayway.jsonpath.JsonPath
                .read(result.andReturn().getResponse().getContentAsString(), "$.id").toString());
    }

    private String statusOf(long bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private long count(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class);
    }

    private static Map<Long, SeatResponse> seatMap(Long... seatIds) {
        Map<Long, SeatResponse> seats = new LinkedHashMap<>();
        for (Long seatId : seatIds) {
            seats.put(seatId, new SeatResponse(
                    seatId, "A", seatId.intValue(), PRICE, SeatResponse.AVAILABLE));
        }
        return seats;
    }

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
}
