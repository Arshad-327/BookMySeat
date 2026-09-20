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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The seat release, through the real endpoint, service and MySQL.
 *
 * <p>Nothing calls this endpoint yet. The sweeper and cancel wire up separately, so this
 * class is the entire proof the endpoint works, and it is written to be that.
 *
 * <h2>What these tests are really protecting</h2>
 * A release that frees a seat it does not own is worse than a release that does nothing: it
 * hands a paid-for seat back to the pool while the booking that bought it still names it in
 * booking_seats.sold_show_seat_id. Most of the cases below are therefore about what the
 * endpoint must NOT do, and only one is about what it does.
 *
 * <p>Every "ignored" case asserts the version as well as the status, because a row re-written
 * with the same values is not an untouched row - it would have moved the version and could
 * have lost somebody else's optimistic-lock race on the way past.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InternalSeatReleaseMySqlTest extends MySqlContainerTest {

    private static final long OWNER = 5L;
    private static final long STRANGER = 9L;

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
    @DisplayName("releases the seats this booking owns: AVAILABLE again, owner cleared, version incremented")
    void releasesSeatsItOwns() throws Exception {
        Long showId = fixtures.createShow("Release Arena", 2);
        List<Long> seats = fixtures.showSeatIds(showId);
        seats.forEach(seat -> fixtures.bookDirectly(seat, OWNER));
        long versionBefore = fixtures.row(seats.get(0)).version();

        release(showId, seats, OWNER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(2))
                .andExpect(jsonPath("$.released").value(2));

        for (Long seat : seats) {
            assertThat(fixtures.row(seat).status()).isEqualTo("AVAILABLE");
            // Owner cleared, not merely ignored. A seat left AVAILABLE while still naming an
            // owner would be indistinguishable from a legacy row on the next pass.
            assertThat(fixtures.row(seat).bookedByBookingId()).isNull();
            // Written through a managed entity, so the version moved: layer 2 is engaged on
            // the release path too, not only on the booking path.
            assertThat(fixtures.row(seat).version()).isEqualTo(versionBefore + 1);
        }
    }

    @Test
    @DisplayName("a seat owned by another booking is skipped, not freed and not an error")
    void ignoresSeatsOwnedByAnotherBooking() throws Exception {
        Long showId = fixtures.createShow("Contested Release Arena", 1);
        Long theirs = fixtures.showSeatIds(showId).get(0);
        fixtures.bookDirectly(theirs, OWNER);
        long versionBefore = fixtures.row(theirs).version();

        // A stale retry: booking 9 asking to release a seat booking 5 has since bought.
        release(showId, List.of(theirs), STRANGER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released").value(0));

        assertThat(fixtures.row(theirs).status()).isEqualTo("BOOKED");
        assertThat(fixtures.row(theirs).bookedByBookingId()).isEqualTo(OWNER);
        assertThat(fixtures.row(theirs).version()).isEqualTo(versionBefore);
    }

    /**
     * THE GUARD. A seat BOOKED with no recorded owner - every row written before
     * V2__seat_booking_owner.sql - must never be freed by a release it cannot possibly have
     * caused.
     *
     * <p>P4.14 measured which hazard is real: a different-owner row is refused by almost any
     * condition anyone writes, and a NULL-owner row is the one a sloppy condition frees.
     *
     * <p>MEASURED HERE TOO. With the predicate loosened to "null or matching", this test went
     * red - released 0 expected, 1 received - and so did
     * {@link #releasesOnlyItsOwnSeatsFromAMix()}, whose fixture includes a legacy row for
     * exactly this reason. Those two were the ONLY failures in the suite.
     * {@link #ignoresSeatsOwnedByAnotherBooking()} passed straight through the broken
     * predicate, because a seat owned by 5 is refused to 9 either way. So this test is not
     * one case among five; it is the case.
     */
    @Test
    @DisplayName("a seat BOOKED with no recorded owner is never freed: unknown ownership is somebody else's")
    void ignoresSeatsWithNoRecordedOwner() throws Exception {
        Long showId = fixtures.createShow("Legacy Release Arena", 1);
        Long legacy = fixtures.showSeatIds(showId).get(0);
        // NULL owner, deliberately and necessarily: this is a pre-V2 row.
        fixtures.bookDirectly(legacy, null);
        long versionBefore = fixtures.row(legacy).version();

        release(showId, List.of(legacy), OWNER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released").value(0));

        assertThat(fixtures.row(legacy).status()).isEqualTo("BOOKED");
        assertThat(fixtures.row(legacy).bookedByBookingId()).isNull();
        assertThat(fixtures.row(legacy).version()).isEqualTo(versionBefore);
    }

    @Test
    @DisplayName("an already AVAILABLE seat is skipped, with no error and no write")
    void ignoresAvailableSeats() throws Exception {
        Long showId = fixtures.createShow("Free Seat Arena", 1);
        Long free = fixtures.showSeatIds(showId).get(0);
        long versionBefore = fixtures.row(free).version();

        release(showId, List.of(free), OWNER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(1))
                .andExpect(jsonPath("$.released").value(0));

        assertThat(fixtures.row(free).status()).isEqualTo("AVAILABLE");
        assertThat(fixtures.row(free).version()).isEqualTo(versionBefore);
    }

    /**
     * THE SWEEPER'S NORMAL CASE, and the reason nothing here throws.
     *
     * <p>ExpiredBookingSweeper will call this for every booking it expires, and almost none
     * of them ever reached the booking endpoint - they expired holding a Redis key and
     * nothing else. If "released 0" were a 404 or a 409, the overwhelmingly common path
     * through this endpoint would be an error path, and the sweeper would spend its life
     * logging failures that mean everything worked.
     */
    @Test
    @DisplayName("a booking that never booked anything releases nothing, and that is a 200")
    void releaseOfABookingThatNeverBookedAnythingSucceeds() throws Exception {
        Long showId = fixtures.createShow("Untouched Arena", 2);
        List<Long> seats = fixtures.showSeatIds(showId);

        release(showId, seats, 424242L)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.showId").value(showId))
                .andExpect(jsonPath("$.requested").value(2))
                .andExpect(jsonPath("$.released").value(0));

        for (Long seat : seats) {
            assertThat(fixtures.row(seat).status()).isEqualTo("AVAILABLE");
            assertThat(fixtures.row(seat).version()).isZero();
        }
    }

    /**
     * The realistic shape: one seat of ours, one of somebody else's, one legacy row and one
     * already free, in a single call.
     *
     * <p>The legacy row in this fixture is not decoration. It makes this the second test that
     * goes red when the ownership predicate is loosened - released 1 expected, 2 received -
     * which is how a mixed call in production would first show the damage.
     */
    @Test
    @DisplayName("a mix releases only this booking's seats and leaves every other row alone")
    void releasesOnlyItsOwnSeatsFromAMix() throws Exception {
        Long showId = fixtures.createShow("Mixed Release Arena", 4);
        List<Long> seats = fixtures.showSeatIds(showId);
        Long ours = seats.get(0);
        Long theirs = seats.get(1);
        Long legacy = seats.get(2);
        Long free = seats.get(3);
        fixtures.bookDirectly(ours, OWNER);
        fixtures.bookDirectly(theirs, STRANGER);
        fixtures.bookDirectly(legacy, null);

        release(showId, seats, OWNER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(4))
                .andExpect(jsonPath("$.released").value(1));

        assertThat(fixtures.row(ours).status()).isEqualTo("AVAILABLE");
        assertThat(fixtures.row(ours).bookedByBookingId()).isNull();
        assertThat(fixtures.row(theirs).status()).isEqualTo("BOOKED");
        assertThat(fixtures.row(theirs).bookedByBookingId()).isEqualTo(STRANGER);
        assertThat(fixtures.row(legacy).status()).isEqualTo("BOOKED");
        assertThat(fixtures.row(legacy).bookedByBookingId()).isNull();
        assertThat(fixtures.row(free).status()).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("an unknown seat id is not an error: a seat that does not exist needs no releasing")
    void ignoresUnknownSeatIds() throws Exception {
        Long showId = fixtures.createShow("Stale Id Arena", 1);
        Long ours = fixtures.showSeatIds(showId).get(0);
        fixtures.bookDirectly(ours, OWNER);

        // Unlike the booking call, which 404s a short count. A stale sweeper call naming a
        // seat that has since been deleted must still release the seats that do exist.
        release(showId, List.of(ours, 999_999L), OWNER)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(2))
                .andExpect(jsonPath("$.released").value(1));

        assertThat(fixtures.row(ours).status()).isEqualTo("AVAILABLE");
    }

    @Test
    @DisplayName("a request with no bookingId is a 400, never a release with no owner to match")
    void rejectsMissingBookingId() throws Exception {
        Long showId = fixtures.createShow("Ownerless Release Arena", 1);
        Long seat = fixtures.showSeatIds(showId).get(0);
        fixtures.bookDirectly(seat, OWNER);

        mockMvc.perform(post("/api/internal/shows/{showId}/seats/release", showId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"showSeatIds\":[" + seat + "]}"))
                .andExpect(status().isBadRequest());

        assertThat(fixtures.row(seat).status()).isEqualTo("BOOKED");
        assertThat(fixtures.row(seat).bookedByBookingId()).isEqualTo(OWNER);
    }

    private ResultActions release(Long showId, List<Long> showSeatIds, long bookingId) throws Exception {
        String ids = showSeatIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        return mockMvc.perform(post("/api/internal/shows/{showId}/seats/release", showId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"showSeatIds\":[" + ids + "],\"bookingId\":" + bookingId + "}"));
    }
}
