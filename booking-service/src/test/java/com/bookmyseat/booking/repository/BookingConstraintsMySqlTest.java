package com.bookmyseat.booking.repository;

import com.bookmyseat.booking.MySqlContainerTest;
import com.bookmyseat.booking.dto.response.ErrorResponse;
import com.bookmyseat.booking.entity.Booking;
import com.bookmyseat.booking.entity.BookingSeat;
import com.bookmyseat.booking.entity.BookingStatus;
import com.bookmyseat.booking.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.math.BigDecimal;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The V2 constraints straight against MySQL, and the handler fed the exception
 * MySQL actually raised - not one constructed by the test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class BookingConstraintsMySqlTest extends MySqlContainerTest {

    private static final BigDecimal PRICE = new BigDecimal("450.00");

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private GlobalExceptionHandler exceptionHandler;

    @Autowired
    private Clock clock;

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
    }

    @Test
    @DisplayName("a reused idempotency key is refused by MySQL, and that exception renders as 409 with a clear message")
    void reusedIdempotencyKeyIsRejected() {
        bookingRepository.saveAndFlush(booking("retry-7f3a", 1L));

        DataIntegrityViolationException violation = catchThrowableOfType(
                () -> bookingRepository.saveAndFlush(booking("retry-7f3a", 2L)),
                DataIntegrityViolationException.class);
        assertThat(violation).as("MySQL must refuse the second booking with this key").isNotNull();

        ResponseEntity<ErrorResponse> response = exceptionHandler.handleDataIntegrity(
                violation, new MockHttpServletRequest("POST", "/api/bookings/hold"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("A booking with this idempotency key already exists");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE idempotency_key = 'retry-7f3a'", Long.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("bookings without an idempotency key never collide: NULLs are distinct in a unique index")
    void nullIdempotencyKeysCoexist() {
        bookingRepository.saveAndFlush(booking(null, 1L));
        bookingRepository.saveAndFlush(booking(null, 2L));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE idempotency_key IS NULL", Long.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("the CHECK keeps the copy honest: sold_show_seat_id cannot name a different seat")
    void soldMarkerMustMatchItsSeat() {
        Long bookingId = bookingRepository.saveAndFlush(booking(null, 3L)).getId();

        // Not a DataIntegrityViolationException: Spring does not classify MySQL error 3819
        // (SQLState HY000) as an integrity violation and reports it as uncategorized -
        // observed on the first run, so the test pins what really happens. That keeps it
        // out of the layer-3 409 handler, which is right: only a code bug can write a
        // wrong copy, and a bug should not be reported to the caller as their conflict.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE booking_seats SET sold_show_seat_id = 4 WHERE booking_id = ?", bookingId))
                .isInstanceOf(org.springframework.jdbc.UncategorizedSQLException.class)
                .hasMessageContaining("chk_booking_seats_sold_matches_seat");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT sold_show_seat_id FROM booking_seats WHERE booking_id = ?", Long.class, bookingId)).isNull();
    }

    private Booking booking(String idempotencyKey, Long showSeatId) {
        Booking booking = new Booking();
        booking.setUserId(7L);
        booking.setShowId(1L);
        booking.setStatus(BookingStatus.PENDING);
        booking.setTotalAmount(PRICE);
        booking.setIdempotencyKey(idempotencyKey);
        booking.setExpiresAt(Instant.now(clock).plus(Duration.ofMinutes(10)));
        BookingSeat seat = new BookingSeat();
        seat.setShowSeatId(showSeatId);
        seat.setPrice(PRICE);
        booking.addSeat(seat);
        return booking;
    }
}
