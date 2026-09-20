package com.bookmyseat.event.controller;

import com.bookmyseat.event.MySqlContainerTest;
import com.bookmyseat.event.SeatFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The strict booking write, through the real endpoint, service, handler and MySQL.
 *
 * <p>Every rejection is checked twice: the status code the caller sees, and the rows
 * in MySQL afterwards - a rejection that still wrote something is not a rejection.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InternalSeatBookingMySqlTest extends MySqlContainerTest {

    private static final long UNKNOWN_SEAT_ID = 999_999L;
    private static final long BOOKING_ID = 4471L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApplicationContext context;

    private SeatFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new SeatFixtures(context);
        fixtures.truncateAll();
    }

    @Test
    @DisplayName("books every requested seat: 200, all BOOKED, each version 0 -> 1")
    void booksEverySeat() throws Exception {
        Long showId = fixtures.createShow("Full Arena", 3);
        List<Long> seats = fixtures.showSeatIds(showId);

        book(showId, seats)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(3))
                .andExpect(jsonPath("$.updated").value(3));

        for (Long seat : seats) {
            assertThat(fixtures.row(seat).status()).isEqualTo("BOOKED");
            assertThat(fixtures.row(seat).version()).isEqualTo(1L);
            // Every seat names the booking it was sold to, not just the first.
            assertThat(fixtures.row(seat).bookedByBookingId()).isEqualTo(BOOKING_ID);
        }
    }

    @Test
    @DisplayName("status guard: a seat already BOOKED is rejected with 409, and nothing in the request is written")
    void rejectsAlreadyBookedSeat() throws Exception {
        Long showId = fixtures.createShow("Guard Arena", 2);
        Long booked = fixtures.showSeatIds(showId).get(0);
        Long free = fixtures.showSeatIds(showId).get(1);
        book(showId, List.of(booked)).andExpect(status().isOk());

        book(showId, List.of(free, booked))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("[" + booked + "] are already BOOKED")));

        assertThat(fixtures.row(free).status()).isEqualTo("AVAILABLE");
        assertThat(fixtures.row(free).version()).isZero();
        assertThat(fixtures.row(booked).version()).isEqualTo(1L);
    }

    @Test
    @DisplayName("short count: asking for three seats when only two exist is rejected with 404, and neither is written")
    void rejectsShortCount() throws Exception {
        Long showId = fixtures.createShow("Short Arena", 2);
        List<Long> seats = fixtures.showSeatIds(showId);

        book(showId, List.of(seats.get(0), seats.get(1), UNKNOWN_SEAT_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString("[" + UNKNOWN_SEAT_ID + "]")));

        for (Long seat : seats) {
            assertThat(fixtures.row(seat).status()).isEqualTo("AVAILABLE");
            assertThat(fixtures.row(seat).version()).isZero();
        }
    }

    @Test
    @DisplayName("short count: a seat belonging to another show is rejected with 404, and nothing is written")
    void rejectsSeatFromAnotherShow() throws Exception {
        Long showA = fixtures.createShow("Arena A", 1);
        Long showB = fixtures.createShow("Arena B", 1);
        Long seatOfA = fixtures.showSeatIds(showA).get(0);
        Long seatOfB = fixtures.showSeatIds(showB).get(0);

        book(showA, List.of(seatOfA, seatOfB))
                .andExpect(status().isNotFound());

        assertThat(fixtures.row(seatOfA).status()).isEqualTo("AVAILABLE");
        assertThat(fixtures.row(seatOfB).status()).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("a repeated id counts once rather than as an unmeetable short count")
    void repeatedIdCountsOnce() throws Exception {
        Long showId = fixtures.createShow("Repeat Arena", 1);
        Long seat = fixtures.showSeatIds(showId).get(0);

        book(showId, List.of(seat, seat))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(1))
                .andExpect(jsonPath("$.updated").value(1));
    }

    @Test
    @DisplayName("a request with no bookingId is a 400, and no seat is booked without an owner")
    void rejectsMissingBookingId() throws Exception {
        Long showId = fixtures.createShow("Ownerless Arena", 2);
        List<Long> seats = fixtures.showSeatIds(showId);

        // The alternative to this 400 would be booking the seats and leaving the owner
        // NULL - manufacturing the orphan the column was added to detect.
        book(showId, seats, "")
                .andExpect(status().isBadRequest());

        for (Long seat : seats) {
            assertThat(fixtures.row(seat).status()).isEqualTo("AVAILABLE");
            assertThat(fixtures.row(seat).bookedByBookingId()).isNull();
        }
    }

    @Test
    @DisplayName("an explicitly null bookingId is a 400 too, not an absent field treated as zero")
    void rejectsNullBookingId() throws Exception {
        Long showId = fixtures.createShow("Null Owner Arena", 1);
        Long seat = fixtures.showSeatIds(showId).get(0);

        book(showId, List.of(seat), "\"bookingId\":null")
                .andExpect(status().isBadRequest());

        assertThat(fixtures.row(seat).status()).isEqualTo("AVAILABLE");
        assertThat(fixtures.row(seat).bookedByBookingId()).isNull();
    }

    @Test
    @DisplayName("an AVAILABLE seat has no owner recorded until it is sold")
    void availableSeatHasNoOwner() throws Exception {
        Long showId = fixtures.createShow("Unsold Arena", 1);
        Long seat = fixtures.showSeatIds(showId).get(0);

        assertThat(fixtures.row(seat).bookedByBookingId()).isNull();
    }

    @Test
    @DisplayName("a BOOKED seat is refused whoever asks - including the booking that already owns it")
    void bookedSeatIsRefusedEvenToItsOwner() throws Exception {
        Long showId = fixtures.createShow("Owner Retry Arena", 1);
        Long seat = fixtures.showSeatIds(showId).get(0);
        book(showId, List.of(seat)).andExpect(status().isOk());

        // The owner column is written and READ BY NOTHING. This asserts today's behaviour
        // on purpose: the same booking asking again is refused exactly as a stranger is.
        // When the read side lands it will change this test, deliberately and visibly.
        book(showId, List.of(seat))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("are already BOOKED")));

        assertThat(fixtures.row(seat).bookedByBookingId()).isEqualTo(BOOKING_ID);
        assertThat(fixtures.row(seat).version()).isEqualTo(1L);
    }

    private ResultActions book(Long showId, List<Long> showSeatIds) throws Exception {
        return book(showId, showSeatIds, "\"bookingId\":" + BOOKING_ID);
    }

    /** Raw body, so a test can send a bookingId this record could not hold - a missing one. */
    private ResultActions book(Long showId, List<Long> showSeatIds, String bookingIdField) throws Exception {
        String ids = showSeatIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        String body = "{\"showSeatIds\":[" + ids + "]"
                + (bookingIdField.isEmpty() ? "" : "," + bookingIdField) + "}";
        return mockMvc.perform(post("/api/internal/shows/{showId}/seats/book", showId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
