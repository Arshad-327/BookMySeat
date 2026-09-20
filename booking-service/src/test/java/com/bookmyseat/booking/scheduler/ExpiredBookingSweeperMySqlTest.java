package com.bookmyseat.booking.scheduler;

import com.bookmyseat.booking.MySqlContainerTest;
import com.bookmyseat.booking.client.EventClient;
import com.bookmyseat.booking.client.dto.SeatsReleasedResponse;
import com.bookmyseat.booking.exception.EventServiceUnavailableException;
import com.bookmyseat.booking.entity.Booking;
import com.bookmyseat.booking.entity.BookingSeat;
import com.bookmyseat.booking.entity.BookingStatus;
import com.bookmyseat.booking.repository.BookingRepository;
import com.bookmyseat.booking.service.BookingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ExpiredBookingSweeper against real MySQL and real Redis, with the Clock pinned.
 *
 * <p>The Clock is fixed at {@link #NOW} so the expiry boundary can be tested to the
 * microsecond, which is the precision TIMESTAMP(6) stores and the precision the
 * {@code :now} parameter is compared at. A real clock could only test "well past" and
 * "well before", and the off-by-one this is guarding against lives exactly at the
 * boundary.
 *
 * <p>Scheduling is off (MySqlContainerTest), so nothing sweeps except these explicit
 * {@code sweep()} calls.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ExpiredBookingSweeperMySqlTest extends MySqlContainerTest {

    private static final long SHOW_ID = 1L;
    private static final BigDecimal PRICE = new BigDecimal("450.00");
    private static final Instant NOW = Instant.parse("2026-09-14T10:00:00.000000Z");

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
    private ExpiredBookingSweeper sweeper;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * Mocked, not stubbed out of the way: the sweeper now calls it on every candidate, and
     * two of the tests below are about exactly what it is asked and what happens when it
     * refuses.
     */
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

        // The normal answer from the release endpoint: nothing to free. Most expiring
        // bookings never reached confirm, so they own no seats in event_db.
        when(eventClient.releaseSeats(anyLong(), anyList(), anyLong()))
                .thenAnswer(invocation -> new SeatsReleasedResponse(invocation.getArgument(0), 1, 0));
    }

    @Test
    @DisplayName("expiry at or before :now is swept, one microsecond after is not")
    void boundaryIsAtOrBeforeNow() {
        Long oneMicroBefore = booking(BookingStatus.PENDING, NOW.minus(1, ChronoUnit.MICROS), 1L);
        Long exactlyNow = booking(BookingStatus.PENDING, NOW, 2L);
        Long oneMicroAfter = booking(BookingStatus.PENDING, NOW.plus(1, ChronoUnit.MICROS), 3L);

        int expired = sweeper.sweep();

        assertThat(expired).isEqualTo(2);
        assertThat(status(oneMicroBefore)).isEqualTo("EXPIRED");
        // Same rule as confirm, which refuses once expiresAt is no longer after now.
        assertThat(status(exactlyNow)).isEqualTo("EXPIRED");
        assertThat(status(oneMicroAfter)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("CONFIRMED and CANCELLED bookings are left alone, even with an expiry in the past")
    void finishedBookingsAreLeftAlone() {
        Instant longAgo = NOW.minus(1, ChronoUnit.HOURS);
        Long confirmed = booking(BookingStatus.CONFIRMED, longAgo, 1L);
        Long cancelled = booking(BookingStatus.CANCELLED, longAgo, 2L);
        Long pending = booking(BookingStatus.PENDING, longAgo, 3L);

        int expired = sweeper.sweep();

        assertThat(expired).isEqualTo(1);
        assertThat(status(confirmed)).isEqualTo("CONFIRMED");
        assertThat(status(cancelled)).isEqualTo("CANCELLED");
        assertThat(status(pending)).isEqualTo("EXPIRED");

        // A second pass finds nothing left to do.
        assertThat(sweeper.sweep()).isZero();
    }

    @Test
    @DisplayName("a booking confirmed after it was picked as a candidate is skipped under the lock")
    void recheckUnderTheLockSkipsABookingThatMovedOn() {
        // The sweeper selected this id while it was PENDING; by the time the per-booking
        // transaction locks it, confirm has committed. Driven directly, because that gap
        // is between two statements inside one sweep.
        Long booking = booking(BookingStatus.CONFIRMED, NOW.minus(1, ChronoUnit.MINUTES), 1L);

        assertThat(bookingService.expireIfPending(booking, NOW)).isFalse();
        assertThat(status(booking)).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("an expired booking's lingering hold is released; a seat since held by another booking is not")
    void releasesOnlyItsOwnLingeringHolds() {
        Long expired = booking(BookingStatus.PENDING, NOW.minus(1, ChronoUnit.MINUTES), 1L, 2L);

        // Seat 1's key lingered past the TTL (no expiry set here, so it cannot vanish
        // mid-test). Seat 2's hold lapsed and booking 999 has taken the seat since.
        redisTemplate.opsForValue().set(holdKey(1L), String.valueOf(expired));
        redisTemplate.opsForValue().set(holdKey(2L), "999");

        assertThat(sweeper.sweep()).isEqualTo(1);

        assertThat(status(expired)).isEqualTo("EXPIRED");
        assertThat(redisTemplate.opsForValue().get(holdKey(1L))).isNull();
        assertThat(redisTemplate.opsForValue().get(holdKey(2L))).isEqualTo("999");
    }

    /**
     * COMPENSATE FIRST, THEN FLIP - asserted as an order, not as two facts.
     *
     * <p>Both calls happening is not the property that matters; the sequence is. Released
     * after the flip, a failure would strand the booking outside the sweeper's own
     * candidate query - it selects PENDING - and nothing would ever retry it.
     */
    @Test
    @DisplayName("seats are released in event-service BEFORE the booking is flipped to EXPIRED")
    void releasesSeatsBeforeFlippingToExpired() {
        Long expiring = booking(BookingStatus.PENDING, NOW.minus(1, ChronoUnit.MINUTES), 4L, 5L);

        AtomicReference<String> statusDuringRelease = new AtomicReference<>();
        AtomicBoolean transactionActiveDuringRelease = new AtomicBoolean(true);
        doAnswer(invocation -> {
            statusDuringRelease.set(status(expiring));
            transactionActiveDuringRelease.set(
                    TransactionSynchronizationManager.isActualTransactionActive());
            return new SeatsReleasedResponse(SHOW_ID, 2, 2);
        }).when(eventClient).releaseSeats(anyLong(), anyList(), anyLong());

        assertThat(sweeper.sweep()).isEqualTo(1);

        // Read from the database at the moment of the release, not from Mockito's call
        // order: the property is that the row is still PENDING while event-service is
        // being asked, which is a fact about the data and not about the mock.
        assertThat(statusDuringRelease.get()).isEqualTo("PENDING");
        verify(eventClient).releaseSeats(eq(SHOW_ID), eq(List.of(4L, 5L)), eq(expiring));
        assertThat(status(expiring)).isEqualTo("EXPIRED");

        // Constraint from the plan, asserted rather than asserted-in-a-comment: the HTTP
        // call runs with no transaction active, so no pooled connection is held across it.
        // seatsOf committed before this point and expireIfPending opens its own after.
        assertThat(transactionActiveDuringRelease.get()).isFalse();
    }

    /**
     * The failure path, and the reason it needs no machinery.
     *
     * <p>The booking stays PENDING, which is the sweeper's own candidate condition, so the
     * next pass sixty seconds later tries the whole thing again. No retry counter and no
     * dead-letter: both halves are idempotent, so repetition is free. A permanently broken
     * event-service piles up PENDING rows rather than EXPIRED ones, which is the safe
     * direction to fail in - the seats stay held rather than being freed while still sold.
     */
    @Test
    @DisplayName("a failed release leaves the booking PENDING and does not flip it, so the next pass retries")
    void failedReleaseLeavesTheBookingPending() {
        Long expiring = booking(BookingStatus.PENDING, NOW.minus(1, ChronoUnit.MINUTES), 6L);
        // doThrow, not when(...).thenThrow: this mock is already stubbed, so when(...) would
        // CALL it and the throw would escape during stubbing rather than during the sweep.
        doThrow(new EventServiceUnavailableException("event-service is down", null))
                .when(eventClient).releaseSeats(anyLong(), anyList(), anyLong());

        // The pass itself does not fail - one broken booking must not stop the others.
        assertThat(sweeper.sweep()).isZero();

        assertThat(status(expiring)).isEqualTo("PENDING");

        // Sixty seconds later, with event-service back: the retry needs nothing but a
        // second call, because the booking is still a candidate.
        doReturn(new SeatsReleasedResponse(SHOW_ID, 1, 1))
                .when(eventClient).releaseSeats(anyLong(), anyList(), anyLong());

        assertThat(sweeper.sweep()).isEqualTo(1);
        assertThat(status(expiring)).isEqualTo("EXPIRED");
    }

    /**
     * The overwhelmingly normal case: a booking that held seats and never confirmed. It
     * owns nothing in event_db, so the release frees nothing - and that is a success, not
     * an error path. If "released 0" were a failure, almost every sweep would log one.
     */
    @Test
    @DisplayName("a booking that never confirmed releases zero seats and expires normally")
    void bookingThatNeverConfirmedReleasesNothingAndStillExpires() {
        Long neverConfirmed = booking(BookingStatus.PENDING, NOW.minus(1, ChronoUnit.MINUTES), 7L);
        doReturn(new SeatsReleasedResponse(SHOW_ID, 1, 0))
                .when(eventClient).releaseSeats(anyLong(), anyList(), anyLong());

        assertThat(sweeper.sweep()).isEqualTo(1);

        // Asked anyway - the sweeper cannot know which bookings orphaned a seat without
        // asking, and asking is what recovers the one that did.
        verify(eventClient).releaseSeats(eq(SHOW_ID), eq(List.of(7L)), eq(neverConfirmed));
        assertThat(status(neverConfirmed)).isEqualTo("EXPIRED");
    }

    private Long booking(BookingStatus status, Instant expiresAt, Long... showSeatIds) {
        Booking booking = new Booking();
        booking.setUserId(7L);
        booking.setShowId(SHOW_ID);
        booking.setStatus(status);
        booking.setTotalAmount(PRICE.multiply(BigDecimal.valueOf(showSeatIds.length)));
        booking.setExpiresAt(expiresAt);
        for (Long showSeatId : showSeatIds) {
            BookingSeat seat = new BookingSeat();
            seat.setShowSeatId(showSeatId);
            seat.setPrice(PRICE);
            booking.addSeat(seat);
        }
        Long id = bookingRepository.save(booking).getId();

        // Guards the boundary test itself: if TIMESTAMP(6) rounded or the driver shifted
        // the zone, the microsecond cases would be testing a different instant.
        assertThat(bookingRepository.findById(id).orElseThrow().getExpiresAt()).isEqualTo(expiresAt);
        return id;
    }

    /** The format SeatHoldService owns. Spelled out here so the test checks it, not reuses it. */
    private static String holdKey(Long seatId) {
        return "seat:hold:" + SHOW_ID + ":" + seatId;
    }

    private String status(Long bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
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
