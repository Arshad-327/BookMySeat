package com.bookmyseat.event.service;

import com.bookmyseat.event.config.SeatBookingFaultProperties;
import com.bookmyseat.event.dto.request.BookSeatsRequest;
import com.bookmyseat.event.dto.request.ReleaseSeatsRequest;
import com.bookmyseat.event.dto.response.SeatsBookedResponse;
import com.bookmyseat.event.dto.response.SeatsReleasedResponse;
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
     * same UPDATE, and that owner is what makes this method IDEMPOTENT.
     *
     * <h2>Idempotent: the same booking may ask twice</h2>
     * A seat is accepted if it is AVAILABLE, or if it is already BOOKED <b>to this very
     * booking</b>. So a caller that never learned the outcome of its first call - review
     * finding #1: event-service committed, booking-service's read timeout had already
     * fired - can repeat the call and be told yes, instead of being refused by the write
     * it made itself. The repeat writes nothing and moves no version. See
     * {@link #isAcceptableFor}, which must be read before this predicate is edited.
     *
     * <p>A seat BOOKED to a DIFFERENT booking is refused exactly as before, and so is a
     * seat BOOKED with no owner recorded. This is still the double-sale guarantee; it is
     * narrower only in that a booking is no longer treated as a stranger to itself.
     *
     * <h2>Strict: rejected, never skipped</h2>
     * The call fails, and nothing is written, unless every requested seat exists in
     * this show and is acceptable:
     * <ul>
     *   <li>an id that is unknown or belongs to another show - 404
     *       ({@link ShowSeatsNotFoundException})
     *   <li>a seat BOOKED to another booking, or BOOKED with no owner - 409
     *       ({@link SeatsAlreadyBookedException})
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

        // Acceptance guard. Checked in Java against the rows as loaded, and still race-free:
        // layer 2 below writes WHERE version = <the version read here>, so a row that changed
        // after this read carries a new version and its UPDATE fails. The guard gives the
        // precise 409 for the common case; the version closes the race.
        List<Long> refused = seats.stream()
                .filter(seat -> !isAcceptableFor(seat, request.bookingId()))
                .map(ShowSeat::getId)
                .toList();
        if (!refused.isEmpty()) {
            throw new SeatsAlreadyBookedException(showId, refused);
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
        //
        // Only the AVAILABLE seats are written. A seat this booking already owns is left
        // exactly as it is - not re-written with the same values - so its version does not
        // move. That is what makes a replay free rather than merely harmless: an untouched
        // row cannot lose an optimistic-lock race it is not running in, and a concurrent
        // writer's view of that row stays valid. InternalSeatBookingMySqlTest asserts the
        // version is unchanged for exactly this reason.
        //
        // PARTIAL MIX. This list can be a strict subset of the request: some seats already
        // ours, some still AVAILABLE. A timeout cannot produce that state - this method
        // commits every seat or none - but the receiver does not assume that. An idempotent
        // receiver that handles only the two pure cases is one partial failure away from
        // being useless, and the partial failure is the case nobody will be watching for.
        List<ShowSeat> toWrite = seats.stream()
                .filter(seat -> seat.getStatus() == SeatStatus.AVAILABLE)
                .toList();

        for (ShowSeat seat : toWrite) {
            seat.setStatus(SeatStatus.BOOKED);
            // The owner, written in the same UPDATE as the status - never a second step.
            // A separate write would leave a window in which the seat is sold and nobody
            // is recorded as having bought it, which is the exact state the column exists
            // to make visible.
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
        //
        // ONLY WHEN SOMETHING WAS ACTUALLY WRITTEN. An idempotent replay - every requested
        // seat already owned by this booking - wrote no rows, so there is no commit for the
        // delay to hold and nothing to arrive late. Delaying it anyway would be modelling a
        // slow CALL when what this reproduces is a slow WRITE, and it would make the
        // recovery untestable: the retry that is supposed to succeed would time out exactly
        // as the first attempt did, and an armed instance could never demonstrate the thing
        // it was armed to demonstrate. scripts/orphan-regression.sh depends on this.
        if (!toWrite.isEmpty()) {
            delayIfArmed(showId, ids);
        }

        // updated == requested on every success, replay included: it answers "how many of
        // the seats you asked for are now BOOKED to you", which is what the caller needs
        // and what booking-service's client documents. It is deliberately NOT a count of
        // rows this call changed - toWrite.size() - because a replay changing nothing is a
        // success, not a partial one, and a caller comparing the two numbers would read it
        // as a failure. The count of rows actually written is a fact about this call, not
        // about the seats, and nothing needs it.
        return new SeatsBookedResponse(showId, ids.size(), seats.size());
    }

    /**
     * Puts seats back: BOOKED to AVAILABLE, owner back to NULL, for the seats this
     * booking actually owns. The compensation for review finding #1.
     *
     * <h2>A SEPARATE METHOD, NOT A MODE ON {@link #markBooked}</h2>
     * The two run in opposite directions under opposite guards - one sells a seat and must
     * never be more permissive, the other un-sells one and must never be more permissive
     * either, but about a different thing. A boolean on one method would put both under one
     * signature, one javadoc and one set of callers, and the method that must never loosen
     * is exactly the method nobody should be adding flags to. Kept apart so that loosening
     * one cannot loosen the other by accident.
     *
     * <h2>Skips, never throws</h2>
     * A seat this booking does not own is passed over silently and left out of the count.
     * Nothing here is an error: not an unknown seat id, not a seat that is already
     * AVAILABLE, not a seat owned by somebody else, not a booking that owns nothing at all.
     * The caller is a compensation path - the sweeper, and cancel - and
     * <b>{@code released: 0} is SUCCESS and is the overwhelmingly normal answer</b>, because
     * almost every expiring booking never reached {@link #markBooked} in the first place.
     * A compensation that can fail needs its own compensation, so this one cannot fail.
     *
     * <p>That is the deliberate opposite of {@link #markBooked}, which rejects a request it
     * cannot satisfy in full. Selling is strict because a short count means a seat was sold
     * that nobody got; releasing is lenient because a short count means there was nothing to
     * undo, which is the state the caller wanted anyway.
     */
    @Transactional
    public SeatsReleasedResponse release(Long showId, ReleaseSeatsRequest request) {
        List<Long> ids = request.showSeatIds().stream().distinct().toList();

        // No short-count check, unlike markBooked. An id that is unknown or belongs to
        // another show simply does not come back, and a seat that does not exist needs no
        // releasing. Turning that into a 404 would make a stale sweeper call an error.
        List<ShowSeat> seats = showSeatRepository.findByShow_IdAndIdIn(showId, ids);

        // ---------------------------------------------------------------------------
        // THE WHERE CLAUSE. The bookingId match is the whole security of this endpoint.
        //
        // WITHOUT IT THIS IS A SEAT-STEALING PRIMITIVE. "Release seat 9001" with no owner
        // check frees whatever is in 9001 at the moment the call lands. A stale retry -
        // the sweeper firing late, a cancel replayed, a queued call arriving after its
        // booking is long gone - would then free a seat that a LATER booking legitimately
        // bought and paid for. That later booking's booking_seats.sold_show_seat_id would
        // still name the seat, while show_seats said AVAILABLE: an owner the seat map
        // denies, and the seat resold to someone else underneath a confirmed booking.
        //
        // With the match, every one of those stale paths is a no-op. The seat belongs to
        // a different booking now, so this call does not match it and does nothing. That
        // is what makes this endpoint safe to call late, twice, or by mistake.
        //
        // NULL OWNER IS NOT A MATCH, and that is the case to get right. A different-owner
        // row is refused by almost any condition anyone writes; a NULL-owner row - every
        // seat sold before V2__seat_booking_owner.sql - is the one a sloppy condition
        // frees. A legacy row cannot have been caused by a release this booking is
        // entitled to make, because nothing recorded that this booking caused anything.
        // Unknown ownership is somebody else's, never ours.
        //
        // Confirmed by running it: loosened to "null or matching", the suite produced two
        // failures, both of them this one hazard -
        // InternalSeatReleaseMySqlTest.ignoresSeatsWithNoRecordedOwner (released 0 expected,
        // 1 received) and releasesOnlyItsOwnSeatsFromAMix (1 expected, 2 received), whose
        // fixture happens to include a legacy row. ignoresSeatsOwnedByAnotherBooking passed
        // straight through the broken predicate, exactly as its counterpart did on the
        // booking side: a seat owned by 5 is refused to 9 under either form. The NULL-owner
        // case is the only thing standing on this line.
        // ---------------------------------------------------------------------------
        List<ShowSeat> ours = seats.stream()
                .filter(seat -> isReleasableBy(seat, request.bookingId()))
                .toList();

        // Managed entities, so @Version engages on every row written - the same layer 2 as
        // markBooked. A bulk JPQL UPDATE would be one statement and would bypass the version
        // check entirely, which is the mistake the booking path was already fixed for.
        for (ShowSeat seat : ours) {
            seat.setStatus(SeatStatus.AVAILABLE);
            // Owner cleared in the same UPDATE as the status. A seat that is AVAILABLE
            // while still naming an owner is the mirror of the orphan this work exists to
            // remove, and it would be indistinguishable from a legacy row afterwards.
            seat.setBookedByBookingId(null);
        }
        showSeatRepository.flush();

        if (!ours.isEmpty()) {
            log.info("released {} seat(s) of show {} held by booking {}: {}",
                    ours.size(), showId, request.bookingId(),
                    ours.stream().map(ShowSeat::getId).toList());
        }

        return new SeatsReleasedResponse(showId, ids.size(), ours.size());
    }

    /**
     * Whether this booking may take this seat back.
     *
     * <p>Positive on BOOKED-and-owned-by-this-booking, and on nothing else. Written the same
     * shape as {@link #isAcceptableFor} deliberately: both are "explicit positive equality,
     * a NULL owner is not a match", and they should stay recognisably the same shape so that
     * loosening either one looks wrong next to the other.
     */
    private static boolean isReleasableBy(ShowSeat seat, Long bookingId) {
        if (seat.getStatus() != SeatStatus.BOOKED) {
            return false;
        }
        return seat.getBookedByBookingId() != null
                && seat.getBookedByBookingId().equals(bookingId);
    }

    /**
     * Whether this request may have this seat.
     *
     * <h2>Accept ONLY on explicit positive equality. Never invert this.</h2>
     * A seat is acceptable if it is AVAILABLE, or if it is BOOKED <b>and names this very
     * booking as its owner</b>. Everything else is refused.
     *
     * <p>The tempting shorter form is "refuse only if the owner differs":
     * <pre>{@code
     *   return seat.getStatus() == SeatStatus.AVAILABLE
     *           || !bookingId.equals(seat.getBookedByBookingId()) == false;  // NO
     *   // or, the form that actually gets written by accident:
     *   return seat.getStatus() == SeatStatus.AVAILABLE
     *           || seat.getBookedByBookingId() == null
     *           || seat.getBookedByBookingId().equals(bookingId);            // NO
     * }</pre>
     * It is one word different in the source and it is a seat-stealing primitive. Every
     * row written before V2__seat_booking_owner.sql has a NULL owner - every seat sold in
     * this system's entire history to that point - and "no recorded owner" would read as
     * "not owned by anyone else", so ANY booking could claim an already-sold seat and the
     * double-sale guarantee would be gone. A NULL owner means <i>unknown</i>, and unknown
     * is refused. The null check below is therefore load-bearing, not defensive padding.
     *
     * <p>Confirmed by running it: with the inversion in place, the whole event-service
     * suite produced exactly ONE failure -
     * {@code InternalSeatBookingMySqlTest.rejectsAlreadyBookedSeat}, 409 expected and 200
     * received. The different-owner test passed, because a seat owned by 5 is refused to
     * 9 under either form. That one narrow test is the entire safety net on this line.
     *
     * <p>{@code bookingId} is non-null - {@link BookSeatsRequest} rejects a null with a 400
     * before this is reached - and the comparison is still written owner-first so that a
     * NULL owner takes the explicit {@code != null} branch rather than depending on which
     * side of {@code equals} it happened to land on.
     */
    private static boolean isAcceptableFor(ShowSeat seat, Long bookingId) {
        if (seat.getStatus() == SeatStatus.AVAILABLE) {
            return true;
        }
        return seat.getBookedByBookingId() != null
                && seat.getBookedByBookingId().equals(bookingId);
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
