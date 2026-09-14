package com.bookmyseat.booking.controller;

import com.bookmyseat.booking.MySqlContainerTest;
import com.bookmyseat.booking.client.EventClient;
import com.bookmyseat.booking.entity.Booking;
import com.bookmyseat.booking.entity.BookingSeat;
import com.bookmyseat.booking.entity.BookingStatus;
import com.bookmyseat.booking.repository.BookingRepository;
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
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/bookings/{id} through the real controller, service, handler and MySQL.
 *
 * <p>The security claim is not "another user gets a 404". It is "another user cannot
 * tell whether the booking exists". A 404 with a different message, body or shape from
 * a genuine 404 would still leak that, so the test compares the two responses whole.
 *
 * <p>The Clock is pinned so the error body's timestamp is the same in both responses.
 * Without that, the bodies could never be byte-identical and the comparison would
 * have to exclude a field - which is exactly the loophole it exists to close.
 */
@SpringBootTest
@AutoConfigureMockMvc
class BookingReadOwnershipMySqlTest extends MySqlContainerTest {

    private static final long SHOW_ID = 1L;
    private static final BigDecimal PRICE = new BigDecimal("450.00");
    private static final Instant NOW = Instant.parse("2026-09-14T10:00:00.000000Z");

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
    private BookingRepository bookingRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Not reached by a read; mocked so the context needs no Redis. */
    @MockBean
    private SeatHoldService seatHoldService;

    /** Not reached by a read; mocked so the context needs no event-service. */
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
    }

    @Test
    @DisplayName("the owner reads their booking: 200 with the booking")
    void ownerReadsTheirBooking() throws Exception {
        Long booking = pendingBooking(7L, 1L, 2L);

        mockMvc.perform(get("/api/bookings/{id}", booking).header("X-User-Id", 7L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(booking))
                .andExpect(jsonPath("$.userId").value(7))
                .andExpect(jsonPath("$.seats.length()").value(2));
    }

    @Test
    @DisplayName("another user's booking gets a response identical to one for an id that does not exist")
    void otherUsersBookingIsIndistinguishableFromMissing() throws Exception {
        Long booking = pendingBooking(7L, 1L);

        // Same id both times, so the path and the id in the message cannot differ for a
        // reason that has nothing to do with existence.
        MockHttpServletResponse notYours = mockMvc
                .perform(get("/api/bookings/{id}", booking).header("X-User-Id", 8L))
                .andReturn().getResponse();

        // Now make the id genuinely not exist, and ask again.
        jdbcTemplate.update("DELETE FROM booking_seats WHERE booking_id = ?", booking);
        jdbcTemplate.update("DELETE FROM bookings WHERE id = ?", booking);
        assertThat(bookingRepository.findById(booking)).isEmpty();

        MockHttpServletResponse missing = mockMvc
                .perform(get("/api/bookings/{id}", booking).header("X-User-Id", 8L))
                .andReturn().getResponse();

        assertThat(missing.getStatus()).isEqualTo(404);
        assertThat(notYours.getStatus()).isEqualTo(missing.getStatus());
        assertThat(notYours.getContentType()).isEqualTo(missing.getContentType());
        // The whole body, byte for byte - timestamp, status, error, message and path.
        assertThat(notYours.getContentAsByteArray()).isEqualTo(missing.getContentAsByteArray());

        // And it is the ordinary not-found body, not something empty that happens to match.
        assertThat(missing.getContentAsString()).isEqualTo("{\"timestamp\":\"2026-09-14T10:00:00Z\","
                + "\"status\":404,\"error\":\"Not Found\",\"message\":\"Booking " + booking + " not found\","
                + "\"path\":\"/api/bookings/" + booking + "\"}");
    }

    @Test
    @DisplayName("a read without X-User-Id is a 400, not an unscoped read")
    void missingUserIdIsRejected() throws Exception {
        Long booking = pendingBooking(7L, 1L);

        mockMvc.perform(get("/api/bookings/{id}", booking))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Required header 'X-User-Id' is missing"));
    }

    private Long pendingBooking(Long userId, Long... showSeatIds) {
        Booking booking = new Booking();
        booking.setUserId(userId);
        booking.setShowId(SHOW_ID);
        booking.setStatus(BookingStatus.PENDING);
        booking.setTotalAmount(PRICE.multiply(BigDecimal.valueOf(showSeatIds.length)));
        booking.setExpiresAt(NOW.plus(Duration.ofMinutes(10)));
        for (Long showSeatId : showSeatIds) {
            BookingSeat seat = new BookingSeat();
            seat.setShowSeatId(showSeatId);
            seat.setPrice(PRICE);
            booking.addSeat(seat);
        }
        return bookingRepository.save(booking).getId();
    }
}
