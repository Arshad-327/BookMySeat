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

    /**
     * THIS TEST NOW PROVES SOMETHING NARROWER THAN ITS NAME SUGGESTS. READ THIS.
     *
     * <p>It used to carry the general proposition "a BOOKED seat is refused". That
     * proposition is FALSE by design as of the idempotency change: a seat BOOKED to the
     * requesting booking is accepted. What this test pins now is the LEGACY-ROW BRANCH -
     * a seat that is BOOKED with no owner recorded is refused - and nothing more.
     *
     * <p>THE FIXTURE'S NULL OWNER IS DELIBERATE AND MUST STAY. The seat is booked here by
     * direct SQL precisely so that booked_by_booking_id is left NULL, reproducing every
     * row written before V2__seat_booking_owner.sql. Booking it through the endpoint
     * instead would stamp it with BOOKING_ID and quietly convert this into a
     * same-owner replay - which returns 200 - so the test would fail loudly, or worse,
     * be "fixed" by changing the expectation.
     *
     * <p>The job this test used to do - proving a sold seat cannot be taken by someone
     * else - now belongs to {@link #rejectsSeatBookedByAnotherBooking()}.
     *
     * <p>NARROWER IS NOT WEAKER, AND THIS IS THE MEASUREMENT THAT SHOWS IT. When the
     * acceptance predicate was deliberately inverted to "refuse only if the owner
     * differs", THIS was the only test in the suite that went red - 409 expected, 200
     * received. The different-owner test passed straight through the broken predicate.
     * This test is the sole guard on the legacy-row hazard the predicate's javadoc
     * describes, so it is load-bearing however narrow its name now reads.
     */
    @Test
    @DisplayName("legacy row: a seat BOOKED with no owner recorded is rejected with 409, and nothing in the request is written")
    void rejectsAlreadyBookedSeat() throws Exception {
        Long showId = fixtures.createShow("Guard Arena", 2);
        Long booked = fixtures.showSeatIds(showId).get(0);
        Long free = fixtures.showSeatIds(showId).get(1);
        fixtures.bookDirectly(booked, null);

        book(showId, List.of(free, booked))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("[" + booked + "] are already BOOKED")));

        assertThat(fixtures.row(free).status()).isEqualTo("AVAILABLE");
        assertThat(fixtures.row(free).version()).isZero();
        // Still ownerless, and untouched by the refused call.
        assertThat(fixtures.row(booked).bookedByBookingId()).isNull();
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

    /**
     * THE DOUBLE-SALE GUARANTEE. This test inherited that job from
     * {@link #rejectsAlreadyBookedSeat()}, which can no longer carry it: that one's seat
     * has a NULL owner and now pins only the legacy-row branch.
     *
     * <p>MEASURED, NOT ASSUMED: this test does NOT catch the inversion the predicate's
     * javadoc warns about. The predicate was deliberately flipped to
     * {@code owner == null || owner.equals(bookingId)} and the whole suite run. This test
     * still passed - correctly, because a seat owned by booking 5 fails
     * {@code 5.equals(9)} under either form. The inversion's damage falls entirely on
     * rows with a NULL owner, and the test that went red was
     * {@link #rejectsAlreadyBookedSeat()} (409 expected, 200 received).
     *
     * <p>So the two tests are not overlapping and neither is redundant: this one pins
     * "another booking's seat is not yours", and that one pins "an unowned sold seat is
     * not yours either". Deleting the narrower-looking one would leave the inversion
     * uncaught by the entire suite.
     */
    @Test
    @DisplayName("a seat BOOKED to another booking is refused with 409, and that booking keeps it")
    void rejectsSeatBookedByAnotherBooking() throws Exception {
        Long showId = fixtures.createShow("Contested Arena", 2);
        Long theirs = fixtures.showSeatIds(showId).get(0);
        Long free = fixtures.showSeatIds(showId).get(1);
        fixtures.bookDirectly(theirs, 5L);
        long versionBefore = fixtures.row(theirs).version();

        // Booking 9 asking for a seat booking 5 owns. Not a replay - a different buyer.
        book(showId, List.of(free, theirs), "\"bookingId\":9")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value(containsString("[" + theirs + "] are already BOOKED by another booking")));

        // The owner is untouched: still booking 5's seat, at the same version.
        assertThat(fixtures.row(theirs).bookedByBookingId()).isEqualTo(5L);
        assertThat(fixtures.row(theirs).version()).isEqualTo(versionBefore);
        // And the refusal was whole: the AVAILABLE seat in the same request stayed free.
        assertThat(fixtures.row(free).status()).isEqualTo("AVAILABLE");
        assertThat(fixtures.row(free).bookedByBookingId()).isNull();
    }

    /**
     * The replay that review finding #1 requires: booking-service's read timeout fired,
     * its confirm rolled back, and it asks again for seats this service already committed
     * to it.
     *
     * <p>The version assertion is the real one. A 200 alone would also be produced by a
     * predicate that re-wrote the row with the same values and got away with it - which
     * would move the version, invalidate any concurrent reader's copy, and make a replay
     * capable of losing an optimistic-lock race it should not be in. Unchanged version is
     * the proof that the no-write path was taken.
     */
    @Test
    @DisplayName("the same booking asking again is accepted, and the row is not re-written")
    void sameBookingIsAcceptedAndWritesNothing() throws Exception {
        Long showId = fixtures.createShow("Replay Arena", 1);
        Long seat = fixtures.showSeatIds(showId).get(0);
        fixtures.bookDirectly(seat, 5L);
        long versionBefore = fixtures.row(seat).version();

        book(showId, List.of(seat), "\"bookingId\":5")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(1))
                // Reports the seat as booked to this caller although it wrote nothing.
                .andExpect(jsonPath("$.updated").value(1));

        assertThat(fixtures.row(seat).status()).isEqualTo("BOOKED");
        assertThat(fixtures.row(seat).bookedByBookingId()).isEqualTo(5L);
        assertThat(fixtures.row(seat).version()).isEqualTo(versionBefore);
    }

    /**
     * A request mixing seats this booking already owns with seats still AVAILABLE.
     *
     * <p>No timeout can produce this state - markBooked commits every seat or none - so
     * this is a state the system is not expected to reach. It is handled anyway: a
     * receiver that only copes with the two pure cases is one partial failure away from
     * being useless, and a partial failure is exactly what nobody will be watching for.
     */
    @Test
    @DisplayName("a mix of seats already owned and seats still free: the free ones are booked, the owned ones untouched")
    void partialMixBooksOnlyTheAvailableSeats() throws Exception {
        Long showId = fixtures.createShow("Mixed Arena", 3);
        List<Long> seats = fixtures.showSeatIds(showId);
        Long alreadyOurs = seats.get(0);
        Long freeOne = seats.get(1);
        Long freeTwo = seats.get(2);
        fixtures.bookDirectly(alreadyOurs, 5L);
        long ownedVersionBefore = fixtures.row(alreadyOurs).version();

        book(showId, seats, "\"bookingId\":5")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(3))
                .andExpect(jsonPath("$.updated").value(3));

        // The one we already had: untouched, version unmoved.
        assertThat(fixtures.row(alreadyOurs).version()).isEqualTo(ownedVersionBefore);
        assertThat(fixtures.row(alreadyOurs).bookedByBookingId()).isEqualTo(5L);
        // The two that were free: written, owned, and version bumped once by this call -
        // the optimistic lock applied to every seat actually written.
        for (Long seat : List.of(freeOne, freeTwo)) {
            assertThat(fixtures.row(seat).status()).isEqualTo("BOOKED");
            assertThat(fixtures.row(seat).bookedByBookingId()).isEqualTo(5L);
            assertThat(fixtures.row(seat).version()).isEqualTo(1L);
        }
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
