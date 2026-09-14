package com.bookmyseat.booking.scheduler;

import com.bookmyseat.booking.MySqlContainerTest;
import com.bookmyseat.booking.client.EventClient;
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

import static org.assertj.core.api.Assertions.assertThat;

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

    /** Never called here; mocked so the context does not reach for a real event-service. */
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
