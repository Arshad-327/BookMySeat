package com.bookmyseat.booking.controller;

import com.bookmyseat.booking.MySqlContainerTest;
import com.bookmyseat.booking.client.EventClient;
import com.bookmyseat.booking.client.dto.SeatsBookedResponse;
import com.bookmyseat.booking.entity.Booking;
import com.bookmyseat.booking.entity.BookingSeat;
import com.bookmyseat.booking.entity.BookingStatus;
import com.bookmyseat.booking.exception.SeatBookingRejectedException;
import com.bookmyseat.booking.repository.BookingRepository;
import com.bookmyseat.booking.service.SeatHoldService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Layer 3 through the real confirm endpoint, service, handler and MySQL.
 *
 * <p>Layer 1 (Redis) and event-service are stubbed to say yes to everything: every
 * hold "still belongs" to its booking and every seat write is accepted. That is
 * exactly the situation layer 3 exists for - the layers in front have let two
 * bookings for one seat through - so the unique index is the only thing left to
 * refuse the second confirmation.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConfirmSoldSeatMySqlTest extends MySqlContainerTest {

    private static final long SHOW_ID = 1L;
    private static final BigDecimal PRICE = new BigDecimal("450.00");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    @MockBean
    private SeatHoldService seatHoldService;

    @MockBean
    private EventClient eventClient;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            // FOREIGN_KEY_CHECKS is per session, so every statement runs on this one connection.
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET FOREIGN_KEY_CHECKS = 0");
                statement.execute("TRUNCATE TABLE booking_seats");
                statement.execute("TRUNCATE TABLE bookings");
                statement.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
            return null;
        });
        when(seatHoldService.seatsNotHeldBy(anyLong(), anyList(), anyLong())).thenReturn(List.of());
        when(eventClient.markSeatsBooked(anyLong(), anyList(), anyLong())).thenAnswer(invocation -> {
            List<?> ids = invocation.getArgument(1);
            return new SeatsBookedResponse(invocation.getArgument(0), ids.size(), ids.size());
        });
    }

    @Test
    @DisplayName("many PENDING rows for one seat coexist; a second confirmation is a 409 from the unique index, never a 500")
    void secondConfirmationForSoldSeatIsRejected() throws Exception {
        Long first = pendingBooking(11L, 1L);
        Long second = pendingBooking(12L, 1L);
        Long third = pendingBooking(13L, 1L);
        // NULL-distinctness: three unconfirmed rows for seat 1, and the index allows them.
        assertThat(count("SELECT COUNT(*) FROM booking_seats WHERE show_seat_id = 1")).isEqualTo(3);

        confirm(first, 11L)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        confirm(second, 12L)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("One or more of these seats has already been sold to another booking"));

        // Refused before event-service was asked: only the first confirmation reached it.
        verify(eventClient, times(1)).markSeatsBooked(anyLong(), anyList(), anyLong());
        // The loser rolled back as one unit - still PENDING, still unsold.
        assertThat(bookingStatus(second)).isEqualTo("PENDING");
        assertThat(soldMarkers(second)).containsOnlyNulls();
        assertThat(bookingStatus(third)).isEqualTo("PENDING");
        // Exactly one sold row for seat 1.
        assertThat(count("SELECT COUNT(*) FROM booking_seats WHERE sold_show_seat_id = 1")).isEqualTo(1);
    }

    @Test
    @DisplayName("a confirmed booking has sold_show_seat_id filled in for every seat, alongside CONFIRMED")
    void confirmationMarksEverySeatSold() throws Exception {
        Long booking = pendingBooking(21L, 6L, 7L);

        confirm(booking, 21L).andExpect(status().isOk());

        assertThat(bookingStatus(booking)).isEqualTo("CONFIRMED");
        assertThat(soldMarkers(booking)).containsExactly(6L, 7L);
    }

    @Test
    @DisplayName("same transaction: a later refusal rolls back the status flip and sold_show_seat_id together")
    void refusalRollsBackStatusAndSoldMarkerTogether() throws Exception {
        Long booking = pendingBooking(31L, 4L, 5L);
        when(eventClient.markSeatsBooked(anyLong(), anyList(), anyLong())).thenThrow(new SeatBookingRejectedException(
                SHOW_ID, List.of(4L, 5L), "Show 1: seat(s) [4] are already BOOKED", null));

        confirm(booking, 31L)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("are already BOOKED")));

        // Both were flushed before event-service was called, and neither survived the
        // rollback: they are one change, not two steps.
        assertThat(bookingStatus(booking)).isEqualTo("PENDING");
        assertThat(soldMarkers(booking)).containsOnlyNulls();
        verify(seatHoldService, never()).releaseSeats(anyLong(), anyList(), anyLong());
    }

    private Long pendingBooking(Long userId, Long... showSeatIds) {
        Booking booking = new Booking();
        booking.setUserId(userId);
        booking.setShowId(SHOW_ID);
        booking.setStatus(BookingStatus.PENDING);
        booking.setTotalAmount(PRICE.multiply(BigDecimal.valueOf(showSeatIds.length)));
        booking.setExpiresAt(Instant.now(clock).plus(Duration.ofMinutes(10)));
        for (Long showSeatId : showSeatIds) {
            BookingSeat seat = new BookingSeat();
            seat.setShowSeatId(showSeatId);
            seat.setPrice(PRICE);
            booking.addSeat(seat);
        }
        return bookingRepository.save(booking).getId();
    }

    private ResultActions confirm(Long bookingId, Long userId) throws Exception {
        return mockMvc.perform(post("/api/bookings/{id}/confirm", bookingId).header("X-User-Id", userId));
    }

    private String bookingStatus(Long bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private List<Long> soldMarkers(Long bookingId) {
        return jdbcTemplate.queryForList(
                "SELECT sold_show_seat_id FROM booking_seats WHERE booking_id = ? ORDER BY show_seat_id",
                Long.class, bookingId);
    }

    private long count(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class);
    }
}
