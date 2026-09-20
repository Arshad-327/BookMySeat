package com.bookmyseat.booking.scheduler;

import com.bookmyseat.booking.client.EventClient;
import com.bookmyseat.booking.client.dto.SeatsReleasedResponse;
import com.bookmyseat.booking.entity.BookingStatus;
import com.bookmyseat.booking.repository.BookingRepository;
import com.bookmyseat.booking.service.BookingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Every 60 seconds, moves PENDING bookings whose hold has lapsed to EXPIRED and releases
 * any hold keys they still own.
 *
 * <h2>This is reconciliation, not the expiry mechanism</h2>
 * <b>Nothing about a seat becoming free again depends on this class.</b> The seat hold is
 * a Redis key with a TTL equal to the booking's expires_at. When that TTL fires, Redis
 * deletes the key and the seat can be held by the next user at that moment, whether
 * this job has run or not. Confirm already refuses a lapsed booking on its own, from
 * expires_at and the missing key. If this sweeper stopped running entirely, no seat would
 * stay locked and none would be double-sold. The only effect would be stale PENDING
 * rows in booking_db.
 *
 * <p>What this adds is an accurate database: a booking that will never be confirmed
 * says EXPIRED instead of PENDING. The hold release is housekeeping on top of that. It
 * only finds a key if the TTL has not collected it yet, and the release script is
 * value-matched, so a seat someone else has held since is never touched.
 *
 * <h2>Single instance only</h2>
 * This assumes exactly one booking-service instance. With two, both would sweep the same
 * candidates. The row lock in {@link BookingService#expireIfPending} keeps that
 * <i>correct</i>, because the second sweeper re-reads EXPIRED and skips it. But it is
 * duplicated work and contention on every cycle. Before scaling out, put a distributed
 * lock around {@link #sweep} so only one instance sweeps per cycle, or run it on only
 * one instance with {@code app.scheduling.enabled=false} on the others.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ExpiredBookingSweeper {

    private final BookingRepository bookingRepository;
    private final BookingService bookingService;
    private final EventClient eventClient;

    /** CLAUDE.md Timekeeping: the expiry comparison reads through the injected Clock. */
    private final Clock clock;

    /**
     * One pass. {@code fixedDelay} rather than {@code fixedRate}: the next run is scheduled
     * 60 seconds after this one finishes, so a slow pass can never overlap the next.
     *
     * @return how many bookings this pass expired
     */
    @Scheduled(fixedDelayString = "PT60S", initialDelayString = "PT60S")
    public int sweep() {
        // Read once, so every candidate in this pass is judged against the same instant,
        // and passed into the query as :now - never SQL NOW().
        Instant now = Instant.now(clock);

        // No index on (status, expires_at), considered and declined: a scan every 60s is cheap at this scale.
        List<Long> candidates = bookingRepository.findIdsByStatusExpiredAt(BookingStatus.PENDING, now);
        if (candidates.isEmpty()) {
            return 0;
        }

        int expired = 0;
        for (Long bookingId : candidates) {
            // One transaction per booking, through the service proxy. One failure must not
            // roll back the rest of the pass, and each booking is locked only while it is
            // being expired, not for the length of the whole sweep.
            try {
                releaseSeats(bookingId);
                if (bookingService.expireIfPending(bookingId, now)) {
                    expired++;
                }
            } catch (RuntimeException ex) {
                // Where the retry chain stops, and it needs no machinery. The booking is
                // still PENDING, so the next pass sixty seconds from now picks it up again.
                // Both halves are idempotent - event-service frees only what this booking
                // owns and says "released 0" the second time, and expireIfPending re-reads
                // the status under the lock - so repeating either is free.
                //
                // No retry counter, no dead-letter, no give-up path, deliberately. A
                // permanently broken event-service means bookings pile up as PENDING rather
                // than EXPIRED, which is the safe direction: a PENDING row is a booking
                // nobody can confirm past its expiry, and the seats it might hold stay held
                // rather than being freed while still sold.
                log.warn("sweep could not expire booking {}; will retry next pass", bookingId, ex);
            }
        }

        log.info("expiry sweep at {}: {} candidate(s), {} expired", now, candidates.size(), expired);
        return expired;
    }

    /**
     * Hands this booking's seats back to event-service, before anything is flipped.
     *
     * <h2>COMPENSATE FIRST, THEN FLIP</h2>
     * If this throws, the booking is left PENDING and the caller's catch logs it. That is
     * the whole error strategy. Flipping first and releasing afterwards would put the
     * booking beyond the sweeper's own candidate query - it only selects PENDING - so a
     * failed release would never be retried by anything.
     *
     * <h2>Almost always a no-op, and that is not a reason to skip it</h2>
     * Most expiring bookings never reached confirm, so they own no seats and this returns
     * {@code released: 0}. It earns its place on the one case that matters: review finding
     * #1, where confirm marked the seats in event_db and then rolled back locally, leaving
     * a PENDING booking whose seats are BOOKED to it and sold to nobody. Nothing else in
     * the system ever frees those.
     *
     * <h2>OUTSIDE ANY TRANSACTION - both calls, deliberately</h2>
     * {@link #sweep} is not transactional, and neither is this method. The booking is read
     * through {@link BookingService#seatsOf}, which opens and commits a transaction of its
     * own and is finished before the HTTP call starts; {@link BookingService#expireIfPending}
     * opens a second one afterwards. So the call to event-service sits BETWEEN two
     * transactions with none of its own, and no pooled connection is held across it.
     * Review finding #5 is about holding a connection across an HTTP call, and the fix for
     * finding #1 must not add another instance of it.
     */
    private void releaseSeats(Long bookingId) {
        // ---------------------------------------------------------------------------
        // NO ROW LOCK IS HELD HERE, AND THAT IS SAFE ONLY BECAUSE OF THE EXPIRY CHECK
        // IN BookingService.confirm. The connection is invisible from either side alone,
        // so the same reasoning is written at that check too.
        //
        // This releases seats for a booking whose row nothing has locked. A concurrent
        // confirm for that same booking is therefore not excluded by any lock - it is
        // excluded by the clock. The candidate query selected only bookings whose
        // expiresAt has already passed, and confirm refuses any booking whose expiresAt
        // has already passed. A booking being released here is a booking confirm will not
        // touch, so the release cannot land between confirm's seat write and its commit.
        //
        // Take the expiry check out of confirm, or widen it by a grace period, and this
        // becomes a race that frees seats under a booking that is about to be CONFIRMED.
        // ---------------------------------------------------------------------------
        bookingService.seatsOf(bookingId).ifPresent(seats -> {
            if (seats.showSeatIds().isEmpty()) {
                return;
            }
            SeatsReleasedResponse response =
                    eventClient.releaseSeats(seats.showId(), seats.showSeatIds(), bookingId);
            // Logged only when it did something. "released 0" is the normal answer and
            // would be noise on every booking of every pass.
            if (response != null && response.released() > 0) {
                log.info("sweep released {} seat(s) of show {} still BOOKED to expiring booking {} "
                                + "- an orphaned seat write, recovered",
                        response.released(), seats.showId(), bookingId);
            }
        });
    }
}
