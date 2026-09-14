package com.bookmyseat.booking.controller;

import com.bookmyseat.booking.MySqlContainerTest;
import com.bookmyseat.booking.client.EventClient;
import com.bookmyseat.booking.client.dto.SeatResponse;
import com.bookmyseat.booking.client.dto.SeatsBookedResponse;
import com.bookmyseat.booking.exception.BookingNotPendingException;
import com.bookmyseat.booking.service.BookingService;
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
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DELETE /api/bookings/{id} through the real controller, service, MySQL and Redis.
 *
 * <p>Unlike the other controller tests, seat holds are NOT stubbed. Cancel exists to
 * free seats at once, and the only way to show that happening is to take real holds
 * with hold_seats.lua and watch release_seats.lua delete them. Only event-service is
 * stubbed.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CancelBookingMySqlTest extends MySqlContainerTest {

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

    @Autowired
    private BookingService bookingService;

    @Autowired
    private Clock clock;

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

        when(eventClient.fetchSeatsById(anyLong())).thenReturn(seatMap(1L, 2L, 3L, 4L, 5L));
        when(eventClient.markSeatsBooked(anyLong(), anyList())).thenAnswer(invocation -> {
            List<?> ids = invocation.getArgument(1);
            return new SeatsBookedResponse(invocation.getArgument(0), ids.size(), ids.size());
        });
    }

    @Test
    @DisplayName("the owner cancels: 200 CANCELLED, its holds are gone at once, another booking's hold survives")
    void ownerCancelReleasesHoldsImmediately() throws Exception {
        long mine = bookingIdFrom(hold(7L, UUID.randomUUID().toString(), 1L, 2L).andExpect(status().isCreated()));
        long theirs = bookingIdFrom(hold(8L, UUID.randomUUID().toString(), 3L).andExpect(status().isCreated()));
        assertThat(holder(1L)).isEqualTo(String.valueOf(mine));
        assertThat(holder(2L)).isEqualTo(String.valueOf(mine));

        cancel(mine, 7L)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(mine))
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(bookingStatus(mine)).isEqualTo("CANCELLED");
        // Checked the moment the response is back - no waiting on a ten-minute TTL.
        assertThat(holder(1L)).isNull();
        assertThat(holder(2L)).isNull();
        assertThat(holder(3L)).isEqualTo(String.valueOf(theirs));

        // Freed for real: a different user can now hold the same seats.
        hold(9L, UUID.randomUUID().toString(), 1L, 2L).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("another user's booking is a 404, not a 403, and nothing about it changes")
    void otherUsersBookingIsNotFound() throws Exception {
        long mine = bookingIdFrom(hold(7L, UUID.randomUUID().toString(), 1L).andExpect(status().isCreated()));

        cancel(mine, 8L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Booking " + mine + " not found"));

        assertThat(bookingStatus(mine)).isEqualTo("PENDING");
        assertThat(holder(1L)).isEqualTo(String.valueOf(mine));
    }

    @Test
    @DisplayName("a CONFIRMED booking cannot be cancelled, and neither can a CANCELLED one twice: 409")
    void onlyPendingCanBeCancelled() throws Exception {
        long confirmed = bookingIdFrom(hold(7L, UUID.randomUUID().toString(), 1L).andExpect(status().isCreated()));
        mockMvc.perform(post("/api/bookings/{id}/confirm", confirmed).header("X-User-Id", 7L))
                .andExpect(status().isOk());

        cancel(confirmed, 7L)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Booking " + confirmed
                        + " is CONFIRMED, not PENDING; only a PENDING booking can be cancelled"));
        assertThat(bookingStatus(confirmed)).isEqualTo("CONFIRMED");

        long cancelled = bookingIdFrom(hold(7L, UUID.randomUUID().toString(), 2L).andExpect(status().isCreated()));
        cancel(cancelled, 7L).andExpect(status().isOk());
        cancel(cancelled, 7L)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Booking " + cancelled
                        + " is CANCELLED, not PENDING; only a PENDING booking can be cancelled"));
    }

    @Test
    @DisplayName("replaying the hold's Idempotency-Key after cancelling returns the cancelled booking, not a new one")
    void replayAfterCancelReturnsTheCancelledBooking() throws Exception {
        String key = UUID.randomUUID().toString();
        long original = bookingIdFrom(hold(7L, key, 1L, 2L).andExpect(status().isCreated()));
        cancel(original, 7L).andExpect(status().isOk());

        hold(7L, key, 1L, 2L)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(original))
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(count("SELECT COUNT(*) FROM bookings")).isEqualTo(1);
        // And the replay did not quietly take the seats back.
        assertThat(holder(1L)).isNull();
        assertThat(holder(2L)).isNull();
    }

    @Test
    @DisplayName("a PENDING booking past its expiry is still cancellable, and cannot release a seat another booking now holds")
    void lapsedPendingIsCancelledWithoutTouchingTheNewHolder() throws Exception {
        long lapsed = bookingIdFrom(hold(7L, UUID.randomUUID().toString(), 1L, 2L).andExpect(status().isCreated()));

        // The hold lapsed and the sweeper has not run: expires_at is in the past, the TTL
        // has collected seat 1, and booking 999 has taken seat 1 since. Seat 2 still
        // lingers under the lapsed booking.
        jdbcTemplate.update("UPDATE bookings SET expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now(clock).minus(Duration.ofMinutes(1))), lapsed);
        redisTemplate.opsForValue().set(holdKey(1L), "999");

        cancel(lapsed, 7L)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(holder(1L)).isEqualTo("999");
        assertThat(holder(2L)).isNull();
    }

    @Test
    @DisplayName("cancel racing confirm waits on the row lock, then sees CONFIRMED and is refused")
    void cancelWaitsForConcurrentConfirm() throws Exception {
        long booking = bookingIdFrom(hold(7L, UUID.randomUUID().toString(), 1L).andExpect(status().isCreated()));

        // Park confirm inside its call to event-service - after it has taken the booking
        // row lock and flushed CONFIRMED, before it commits.
        CountDownLatch confirmInsideEventService = new CountDownLatch(1);
        CountDownLatch letConfirmFinish = new CountDownLatch(1);
        when(eventClient.markSeatsBooked(anyLong(), anyList())).thenAnswer(invocation -> {
            confirmInsideEventService.countDown();
            assertThat(letConfirmFinish.await(30, TimeUnit.SECONDS)).isTrue();
            return new SeatsBookedResponse(SHOW_ID, 1, 1);
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<?> confirm = CompletableFuture.supplyAsync(
                    () -> bookingService.confirm(7L, booking), executor);
            assertThat(confirmInsideEventService.await(30, TimeUnit.SECONDS)).isTrue();

            CompletableFuture<?> cancel = CompletableFuture.supplyAsync(
                    () -> bookingService.cancel(7L, booking), executor);

            // Without the lock, cancel would read the committed PENDING row and finish
            // straight away. With it, cancel cannot finish while confirm is still open.
            assertThatThrownBy(() -> cancel.get(2, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);

            letConfirmFinish.countDown();
            confirm.get(30, TimeUnit.SECONDS);

            assertThatThrownBy(() -> cancel.get(30, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(BookingNotPendingException.class)
                    .cause().hasMessageContaining("is CONFIRMED");
        } finally {
            letConfirmFinish.countDown();
            executor.shutdownNow();
        }

        assertThat(bookingStatus(booking)).isEqualTo("CONFIRMED");
    }

    private ResultActions hold(Long userId, String idempotencyKey, Long... seatIds) throws Exception {
        StringBuilder seats = new StringBuilder();
        for (int i = 0; i < seatIds.length; i++) {
            seats.append(i > 0 ? "," : "").append(seatIds[i]);
        }
        return mockMvc.perform(post("/api/bookings/hold")
                .header("X-User-Id", userId)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"showId\":" + SHOW_ID + ",\"seatIds\":[" + seats + "]}"));
    }

    private ResultActions cancel(Long bookingId, Long userId) throws Exception {
        return mockMvc.perform(delete("/api/bookings/{id}", bookingId).header("X-User-Id", userId));
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

    /** The format SeatHoldService owns. Spelled out here so the test checks it, not reuses it. */
    private static String holdKey(Long seatId) {
        return "seat:hold:" + SHOW_ID + ":" + seatId;
    }

    private String holder(Long seatId) {
        return redisTemplate.opsForValue().get(holdKey(seatId));
    }

    private String bookingStatus(Long bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private long count(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class);
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
