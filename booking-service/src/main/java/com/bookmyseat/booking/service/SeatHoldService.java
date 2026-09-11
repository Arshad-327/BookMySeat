package com.bookmyseat.booking.service;

import com.bookmyseat.booking.config.SeatHoldProperties;
import com.bookmyseat.booking.exception.HoldUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Seat holds in Redis. The only place that knows the key format.
 *
 * <h2>Redis failure posture: this FAILS CLOSED</h2>
 * If Redis cannot be reached, every method here throws
 * {@link HoldUnavailableException}, which the caller turns into 503 and no
 * booking is written. There is deliberately no fallback path that books a seat
 * without a hold.
 *
 * <p><b>Why that differs from a rate limiter, which fails OPEN.</b> The two look
 * like the same kind of Redis dependency and need opposite answers, because the
 * cost of being wrong is not symmetric:
 *
 * <ul>
 *   <li>A rate limiter's counter is <i>advisory</i>. If Redis is down and the
 *       limiter cannot count, letting requests through means some caller briefly
 *       exceeds their quota. The damage is bounded, self-correcting the moment
 *       Redis returns, and reversible. Refusing all traffic instead would convert
 *       a Redis outage into a total outage - the protection would cause a worse
 *       failure than the thing it protects against. So it fails open.
 *   <li>A seat hold is <i>the</i> mutual exclusion during checkout. It decides which
 *       one user may go on to pay for a seat. If Redis is down and we book anyway,
 *       every contender gets through to confirm. Layers 2 and 3 - the optimistic
 *       lock on show_seats and the unique index on booking_seats.sold_show_seat_id -
 *       would still refuse all but one, so the seat is not sold twice; but everyone
 *       else would complete checkout for a seat and lose it at the final step. So it
 *       fails closed.
 * </ul>
 *
 * <p>The rule this follows: fail open when the mechanism is advisory and its absence
 * only degrades service; fail closed when its absence breaks the promise made to the
 * user - here, that the seat in their checkout is theirs. Before the database
 * constraints existed, the hold was also the only thing preventing a double sale.
 * It no longer is, which makes failing open a defensible future choice rather than
 * a data-corruption bug; it is not the choice made today. A 503 tells the user to
 * try again in a minute; losing a seat after entering payment details is worse.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SeatHoldService {

    /** seat:hold:{showId}:{seatId} - built here and nowhere else. */
    private static final String KEY_PREFIX = "seat:hold:";
    private static final String KEY_SEPARATOR = ":";

    private final StringRedisTemplate redisTemplate;

    @SuppressWarnings("rawtypes")
    private final RedisScript<List> holdSeatsScript;

    private final RedisScript<Long> releaseSeatsScript;

    private final SeatHoldProperties properties;

    /**
     * What happened when holds were attempted.
     *
     * <p>A record rather than a boolean plus an out-parameter: the two fields are
     * only ever meaningful together, and {@code conflictingSeatIds} is empty
     * exactly when {@code acquired} is true.
     */
    public record HoldResult(boolean acquired, List<Long> conflictingSeatIds) {

        static HoldResult success() {
            return new HoldResult(true, List.of());
        }

        static HoldResult conflict(List<Long> conflictingSeatIds) {
            return new HoldResult(false, List.copyOf(conflictingSeatIds));
        }
    }

    /**
     * Attempts to hold every seat for this booking. All or nothing.
     *
     * @return {@link HoldResult#acquired()} true when every seat is now held by
     *         this booking; otherwise the ids of the seats someone else holds,
     *         with nothing left held by this booking.
     * @throws HoldUnavailableException if Redis could not be reached - see the
     *         class note on failing closed.
     */
    @SuppressWarnings("unchecked")
    public HoldResult holdSeats(Long showId, List<Long> seatIds, Long bookingId) {
        List<String> keys = keysFor(showId, seatIds);
        long ttlSeconds = properties.ttl().toSeconds();

        List<String> takenKeys;
        try {
            takenKeys = redisTemplate.execute(
                    holdSeatsScript,
                    keys,
                    String.valueOf(bookingId),
                    String.valueOf(ttlSeconds));
        } catch (DataAccessException ex) {
            // Covers connection refusal, timeouts and Lettuce errors alike -
            // Spring translates them all into DataAccessException subtypes.
            throw new HoldUnavailableException(
                    "Redis unavailable while holding seats " + seatIds + " for show " + showId, ex);
        }

        // A null reply would mean the script returned nothing at all, which it
        // cannot - it returns an empty table on success. Treated as a failure
        // rather than assumed to be success, because assuming success here is
        // precisely the double-sale this class exists to prevent.
        if (takenKeys == null) {
            throw new HoldUnavailableException(
                    "hold_seats.lua returned no reply for show " + showId, null);
        }

        if (takenKeys.isEmpty()) {
            log.debug("booking {} holds {} seat(s) on show {} for {}s",
                    bookingId, seatIds.size(), showId, ttlSeconds);
            return HoldResult.success();
        }

        List<Long> conflicting = takenKeys.stream().map(SeatHoldService::seatIdFromKey).toList();
        log.info("booking {} could not hold seat(s) {} on show {} - already held",
                bookingId, conflicting, showId);
        return HoldResult.conflict(conflicting);
    }

    /**
     * Releases this booking's holds. Never touches another booking's.
     *
     * <p>Best effort by design: this is cleanup, and the TTL is the guarantee that
     * holds do not leak. A failure is logged and swallowed rather than propagated,
     * because it happens after the booking is already committed and turning a
     * successful confirm into an error would be a lie.
     */
    public void releaseSeats(Long showId, List<Long> seatIds, Long bookingId) {
        List<String> keys = keysFor(showId, seatIds);
        try {
            Long released = redisTemplate.execute(
                    releaseSeatsScript, keys, String.valueOf(bookingId));
            log.debug("booking {} released {} of {} hold(s) on show {}",
                    bookingId, released, keys.size(), showId);
        } catch (DataAccessException ex) {
            log.warn("booking {}: could not release holds on show {} for seats {}. "
                            + "They will expire on their own within the TTL.",
                    bookingId, showId, seatIds, ex);
        }
    }

    /**
     * Which of these seats are NOT currently held by this booking.
     *
     * <p>Used by the confirm path to check the holds survived. One MGET rather
     * than a key at a time, so the whole set is read in a single round trip and
     * cannot drift between reads.
     *
     * <p><b>This is a guard, not a guarantee, and the difference matters.</b> The
     * check and the database write that follows it cannot be made atomic with each
     * other - they are two different systems. In principle a hold could expire in
     * the gap between this returning empty and the seats being marked BOOKED. What
     * closes that hole is the optimistic lock on the show_seats row, which is why
     * the confirm path writes through managed entities rather than a bulk update.
     * This check exists to reject the common case early and cheaply, with a clear
     * error, instead of letting it fail deeper in.
     *
     * @throws HoldUnavailableException if Redis could not be reached
     */
    public List<Long> seatsNotHeldBy(Long showId, List<Long> seatIds, Long bookingId) {
        List<String> keys = keysFor(showId, seatIds);

        List<String> owners;
        try {
            owners = redisTemplate.opsForValue().multiGet(keys);
        } catch (DataAccessException ex) {
            throw new HoldUnavailableException(
                    "Redis unavailable while verifying holds for booking " + bookingId, ex);
        }

        if (owners == null) {
            throw new HoldUnavailableException(
                    "Redis returned no reply verifying holds for booking " + bookingId, null);
        }

        String expectedOwner = String.valueOf(bookingId);
        List<Long> lost = new ArrayList<>();
        for (int i = 0; i < seatIds.size(); i++) {
            // multiGet returns a null entry for a key that does not exist, which
            // is the expired case. Anything else means another booking holds it.
            if (!expectedOwner.equals(owners.get(i))) {
                lost.add(seatIds.get(i));
            }
        }
        return lost;
    }

    /** seat:hold:{showId}:{seatId}, in list order so replies line up with the input. */
    private static List<String> keysFor(Long showId, List<Long> seatIds) {
        return seatIds.stream()
                .map(seatId -> KEY_PREFIX + showId + KEY_SEPARATOR + seatId)
                .toList();
    }

    /**
     * The seat id is the last segment of the key.
     *
     * <p>The script returns keys, not seat ids, so that the key format stays owned
     * by this class alone - the Lua never has to know how a key is composed.
     */
    private static Long seatIdFromKey(String key) {
        return Long.valueOf(key.substring(key.lastIndexOf(KEY_SEPARATOR) + 1));
    }
}
