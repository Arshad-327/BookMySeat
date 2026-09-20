package com.bookmyseat.event.service;

import com.bookmyseat.event.MySqlContainerTest;
import com.bookmyseat.event.SeatFixtures;
import com.bookmyseat.event.config.SeatBookingFaultProperties;
import com.bookmyseat.event.dto.request.BookSeatsRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The seat write runs at full speed. Nothing slows it down, and nothing can without
 * being set deliberately from a command line.
 *
 * <h2>Why this test exists at all</h2>
 * {@link SeatBookingFaultProperties} lets a reproduction run park the internal seat
 * write mid-transaction, to demonstrate review finding #1 against a real commit in a
 * real database. That knob is kept rather than deleted, because the regression it
 * reproduces is a race that no mock can stage and deleting the knob would turn the
 * reproduction into a comment describing one.
 *
 * <p>A knob that can slow the highest-value write in the system needs a standing
 * statement that it is off. This is that statement, asserted against the properties the
 * application's own configuration produces - not against a test fixture - so that
 * anything that ever sets {@code app.fault.book-seats-delay} in application.yml, a
 * profile or a default fails here rather than shipping.
 */
@SpringBootTest
class SeatWriteHasNoArtificialDelayTest extends MySqlContainerTest {

    @Autowired
    private SeatBookingFaultProperties faultProperties;

    @Autowired
    private InternalSeatService internalSeatService;

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("the running application configures no delay on the seat write: PT0S, and not armed")
    void applicationConfiguresNoDelay() {
        assertThat(faultProperties.bookSeatsDelay()).isEqualTo(Duration.ZERO);
        assertThat(faultProperties.isArmed()).isFalse();
    }

    @Test
    @DisplayName("booking seats goes straight through on the configured defaults, and records the owner")
    void bookingSeatsRunsUndelayed() {
        SeatFixtures fixtures = new SeatFixtures(context);
        fixtures.truncateAll();
        Long showId = fixtures.createShow("Undelayed Arena", 2);
        List<Long> seats = fixtures.showSeatIds(showId);

        internalSeatService.markBooked(showId, new BookSeatsRequest(seats, 4471L));

        // No timing assertion, deliberately: a stopwatch bound would be a flake on a busy
        // machine and would prove nothing that isArmed() does not already prove. What this
        // asserts is that the real write path, with the real configuration, completes and
        // commits the rows it was asked for.
        for (Long seat : seats) {
            assertThat(fixtures.row(seat).status()).isEqualTo("BOOKED");
            assertThat(fixtures.row(seat).bookedByBookingId()).isEqualTo(4471L);
        }
    }

    @Test
    @DisplayName("an unset property binds to zero rather than to null, so the write path cannot NPE on it")
    void unsetPropertyBindsToZero() {
        // Spring hands the record constructor null for a property nobody set. That is the
        // ordinary case - every run but a reproduction - so it is pinned here rather than
        // left to the compact constructor's good intentions.
        assertThat(new SeatBookingFaultProperties(null).bookSeatsDelay()).isEqualTo(Duration.ZERO);
        assertThat(new SeatBookingFaultProperties(null).isArmed()).isFalse();
        // A negative duration is not a delay either. Thread.sleep would throw on one.
        assertThat(new SeatBookingFaultProperties(Duration.ofSeconds(-1)).isArmed()).isFalse();
    }
}
