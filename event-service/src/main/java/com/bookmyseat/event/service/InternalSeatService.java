package com.bookmyseat.event.service;

import com.bookmyseat.event.config.SeatBookingFaultProperties;
import com.bookmyseat.event.dto.request.BookSeatsRequest;
import com.bookmyseat.event.dto.response.SeatsBookedResponse;
import com.bookmyseat.event.entity.SeatStatus;
import com.bookmyseat.event.entity.ShowSeat;
import com.bookmyseat.event.exception.SeatsAlreadyBookedException;
import com.bookmyseat.event.exception.ShowSeatsNotFoundException;
import com.bookmyseat.event.repository.ShowSeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class InternalSeatService {

    private final ShowSeatRepository showSeatRepository;

    /** Fault injection, {@code PT0S} in every run but a deliberate reproduction. */
    private final SeatBookingFaultProperties faultProperties;

    /**
     * Marks seats BOOKED - every requested seat, or none of them.
     *
     * <p>Every seat it marks BOOKED also records {@code bookingId} as its owner, in the
     * same UPDATE. Nothing in this service reads that column yet - the refusal rules below
     * are exactly what they were before it existed.
     *
     * <h2>Strict: rejected, never skipped</h2>
     * The call fails, and nothing is written, unless every requested seat exists in
     * this show and is AVAILABLE:
     * <ul>
     *   <li>an id that is unknown or belongs to another show - 404
     *       ({@link ShowSeatsNotFoundException})
     *   <li>a seat that is already BOOKED - 409 ({@link SeatsAlreadyBookedException})
     *   <li>a row that changed after it was read - 409, from the optimistic lock
     * </ul>
     * All three used to be tolerated: a BOOKED seat was skipped, and a short count was
     * logged and handed back as a number. Asking for three seats and changing two then
     * looked like success, and a booking could be confirmed for a seat it never got.
     *
     * <h2>Why managed entities, not a bulk UPDATE</h2>
     * A bulk JPQL UPDATE bypasses {@code @Version} entirely: Hibernate neither reads,
     * checks nor increments it. The baseline run proved the lock was inert that way -
     * ten bookings for one seat and {@code version} still 0. Loading the rows costs a
     * SELECT and one UPDATE per seat, and buys the version check on every one.
     */
    @Transactional
    public SeatsBookedResponse markBooked(Long showId, BookSeatsRequest request) {
        // Distinct: an id repeated in the request is one seat, not a count that could
        // never be met.
        List<Long> ids = request.showSeatIds().stream().distinct().toList();
        List<ShowSeat> seats = showSeatRepository.findByShow_IdAndIdIn(showId, ids);

        // Short count. The lookup is scoped to showId, so an id it did not return is
        // unknown or belongs to another show. Rejected before anything is mutated.
        if (seats.size() != ids.size()) {
            Set<Long> found = seats.stream().map(ShowSeat::getId).collect(Collectors.toSet());
            List<Long> missing = ids.stream().filter(id -> !found.contains(id)).toList();
            throw new ShowSeatsNotFoundException(showId, missing);
        }

        // status = AVAILABLE guard. Checked in Java against the rows as loaded, and still
        // race-free: layer 2 below writes WHERE version = <the version read here>, so a row
        // that became BOOKED after this read carries a new version and its UPDATE fails.
        // The guard gives the precise 409 for the common case; the version closes the race.
        List<Long> alreadyBooked = seats.stream()
                .filter(seat -> seat.getStatus() != SeatStatus.AVAILABLE)
                .map(ShowSeat::getId)
                .toList();
        if (!alreadyBooked.isEmpty()) {
            throw new SeatsAlreadyBookedException(showId, alreadyBooked);
        }

        // ---------------------------------------------------------------------------
        // LAYER 2 of 3 - OPTIMISTIC LOCK (@Version on show_seats)
        // Protects against: two writers selling the same seat concurrently - e.g. two
        // confirms racing after a Redis hold (layer 1) was lost - where both read the
        // seat as AVAILABLE before either wrote. Each UPDATE Hibernate emits is
        //     UPDATE show_seats SET status = 'BOOKED', version = N + 1
        //      WHERE id = ? AND version = N
        // so only the first writer matches a row. Hibernate checks the row count itself:
        // zero rows is an optimistic-lock failure, rendered as 409, and the whole call
        // rolls back. That is the SQL half of the short-count rule. Proven against real
        // MySQL by ShowSeatOptimisticLockTest.
        // ---------------------------------------------------------------------------
        for (ShowSeat seat : seats) {
            seat.setStatus(SeatStatus.BOOKED);
            // The owner, written in the same UPDATE as the status - never a second step.
            // A separate write would leave a window in which the seat is sold and nobody
            // is recorded as having bought it, which is the exact state the column exists
            // to make visible.
            //
            // WRITTEN AND NOT READ. The AVAILABLE guard above is unchanged: a BOOKED seat
            // is still refused whoever owns it, including the booking that owns it. The
            // read side is a separate change.
            seat.setBookedByBookingId(request.bookingId());
        }
        // Flushed here rather than at commit, so a stale version throws inside this
        // method and is attributable to this call in the log.
        showSeatRepository.flush();

        // Fault injection, and nothing else. See SeatBookingFaultProperties: PT0S unless a
        // reproduction run armed it, and at PT0S this is one isZero() branch with no sleep.
        //
        // Placed HERE deliberately - after the flush, before the return that commits. The
        // rows are written and the version checked; the transaction has not committed yet.
        // A caller whose read timeout fires during this sleep gives up and rolls ITS side
        // back, and this transaction then commits anyway. That is exactly the orphan review
        // finding #1 predicts, reproduced in the real write path rather than mocked.
        delayIfArmed(showId, ids);

        return new SeatsBookedResponse(showId, ids.size(), seats.size());
    }

    /**
     * Sleeps for {@code app.fault.book-seats-delay}, if a reproduction run set one.
     *
     * <p>Logged at WARN on every call, not once at startup: an instance running with this
     * armed is not a healthy instance, and the line has to appear next to the request it
     * distorted for anyone reading the log afterwards to know which it was.
     */
    private void delayIfArmed(Long showId, List<Long> ids) {
        if (!faultProperties.isArmed()) {
            return;
        }
        log.warn("FAULT INJECTION ACTIVE: holding the committed-but-uncommitted seat write for "
                        + "show {} seats {} for {} before commit. app.fault.book-seats-delay is set; "
                        + "this instance is deliberately broken and must not be treated as healthy.",
                showId, ids, faultProperties.bookSeatsDelay());
        try {
            Thread.sleep(faultProperties.bookSeatsDelay().toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during injected seat-booking delay", ex);
        }
    }
}
