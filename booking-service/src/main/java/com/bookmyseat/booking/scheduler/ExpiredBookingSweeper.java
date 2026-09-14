package com.bookmyseat.booking.scheduler;

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
                if (bookingService.expireIfPending(bookingId, now)) {
                    expired++;
                }
            } catch (RuntimeException ex) {
                log.warn("sweep could not expire booking {}; will retry next pass", bookingId, ex);
            }
        }

        log.info("expiry sweep at {}: {} candidate(s), {} expired", now, candidates.size(), expired);
        return expired;
    }
}
