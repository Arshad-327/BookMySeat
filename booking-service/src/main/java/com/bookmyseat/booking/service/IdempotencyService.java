package com.bookmyseat.booking.service;

import com.bookmyseat.booking.config.IdempotencyProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * The idempotency fast path: which booking a given Idempotency-Key already produced.
 *
 * <p>One key per request <b>per operation</b>:
 * {@code idem:{operation}:{key} -> bookingId}, written with {@code SET NX EX}. That
 * is a single atomic Redis command, so no Lua script is needed here - unlike a seat
 * hold, which has to take several keys all or nothing.
 *
 * <h2>Why the operation is part of the key</h2>
 * <b>hold and confirm must not share a namespace.</b> They once did, under a bare
 * {@code idem:{key}}, and a client that sent one key for both steps of its checkout -
 * which is the obvious thing for a client to do - found that its confirm hit the entry
 * its own hold had just written. A cache hit returns the cached booking without
 * calling the operation at all, so the booking stayed PENDING while the caller was
 * told 200. Review finding #2, docs/review-2026-09-18.md.
 *
 * <p>Namespacing by {@link Operation} makes one key two independent records that
 * cannot shadow each other, and it is the whole of the fix: a key is only ever a
 * replay of the <em>same</em> operation. The key itself is a canonical UUID - see
 * {@code BookingController.canonicalKey} - so it can never contain a colon and no
 * caller-supplied value can forge a segment.
 *
 * <h2>Redis failure posture: this FAILS OPEN</h2>
 * If Redis cannot be reached, every method here degrades quietly - a lookup reports
 * "not seen before" and a write is dropped - and the request carries on to the
 * insert. It never throws, and it never turns a Redis outage into a failed booking.
 *
 * <p><b>This is the third deliberate failure posture in this codebase, and they do
 * not agree with each other:</b>
 *
 * <ul>
 *   <li>the rate limiter fails <b>open</b>,</li>
 *   <li>the seat hold ({@link SeatHoldService}) fails <b>closed</b>,</li>
 *   <li>idempotency, here, fails <b>open</b>.</li>
 * </ul>
 *
 * <p>All three are Redis. The datastore is not what decides, and reading them as
 * "how we treat Redis" gets the next one wrong. <b>The posture follows from what the
 * mechanism guarantees.</b> Fail closed only where the unavailable mechanism IS the
 * guarantee, because proceeding without it means proceeding with nothing; fail open
 * where the mechanism is an optimisation in front of a guarantee that still holds
 * without it.
 *
 * <p>Applied to each: a seat hold IS the mutual exclusion during checkout, so losing
 * it and booking anyway means every contender walks into payment for one seat - it
 * fails closed. A rate-limit counter is advisory, and refusing all traffic to protect
 * a quota converts a Redis outage into a total outage - it fails open. And this one
 * is advisory too, because <b>the guarantee is the unique index on
 * bookings.idempotency_key, which is in MySQL and entirely unaffected by Redis being
 * down.</b> With Redis gone a replay simply takes the slow path: it attempts the
 * insert, the constraint refuses it, and
 * {@link IdempotentBookingService} returns the original booking. The caller cannot
 * tell the difference except in latency. Failing closed here would refuse bookings to
 * protect an invariant that was never at risk.
 *
 * <p>So the rule, stated once: <b>the posture follows from what the mechanism
 * guarantees, not from which datastore it uses.</b> Redis being unavailable is only
 * allowed to fail closed where Redis is the guarantee - and here it is not.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    /** idem:{operation}:{key} - built here and nowhere else. */
    private static final String KEY_PREFIX = "idem:";

    /**
     * Which endpoint a remembered key belongs to.
     *
     * <p>The segment is part of the Redis key, so these names are wire format, not
     * labels: renaming one strands every key already written under the old spelling
     * (harmlessly - see {@link com.bookmyseat.booking.config.IdempotencyProperties} -
     * but pointlessly).
     */
    public enum Operation {

        /** POST /api/bookings/hold. Backed by the unique index on bookings.idempotency_key. */
        HOLD("hold"),

        /** POST /api/bookings/{id}/confirm. Redis only; a convenience, not a guarantee. */
        CONFIRM("confirm");

        private final String segment;

        Operation(String segment) {
            this.segment = segment;
        }
    }

    private final StringRedisTemplate redisTemplate;

    private final IdempotencyProperties properties;

    /**
     * The booking this key already produced, if Redis remembers one.
     *
     * <p>An empty result means "no fast-path answer" - which covers both "this key is
     * genuinely new" and "Redis could not tell us". The two are deliberately not
     * distinguished, because the caller does the same thing either way: try the
     * insert and let the unique index decide.
     */
    public Optional<Long> findBookingId(Operation operation, String key) {
        String value;
        try {
            value = redisTemplate.opsForValue().get(redisKey(operation, key));
        } catch (DataAccessException ex) {
            // Fails open - see the class note. The constraint still guards the insert.
            log.warn("Redis unavailable reading {} idempotency key {}; falling through to "
                    + "the unique index on bookings.idempotency_key", operation, key, ex);
            return Optional.empty();
        }

        if (value == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(Long.valueOf(value));
        } catch (NumberFormatException ex) {
            // Only reachable if something other than this class wrote the key. Treated
            // as a miss rather than an error: the slow path gives the right answer.
            log.warn("{} idempotency key {} holds a non-numeric booking id [{}]; ignoring it",
                    operation, key, value);
            return Optional.empty();
        }
    }

    /**
     * Remembers that this key produced this booking. Never overwrites an existing
     * entry, and never throws.
     *
     * <p>{@code SET NX} rather than a plain {@code SET}: if two requests carrying the
     * same key raced, exactly one of them created a booking and the other is about to
     * be handed that same booking id, so the first value written is the right one and
     * a blind overwrite could only replace it with a losing booking's id.
     *
     * @return true if this call wrote the entry; false if one was already present or
     *         Redis could not be reached. Informational - no caller has to act on it.
     */
    public boolean record(Operation operation, String key, Long bookingId) {
        try {
            Boolean written = redisTemplate.opsForValue()
                    .setIfAbsent(redisKey(operation, key), String.valueOf(bookingId), properties.ttl());
            return Boolean.TRUE.equals(written);
        } catch (DataAccessException ex) {
            // Fails open. The booking is already committed and the key is already in
            // bookings.idempotency_key; losing the cache entry costs a replay one round
            // trip through the constraint, so turning this into an error would report a
            // failure for a request that completely succeeded.
            log.warn("Redis unavailable recording {} idempotency key {} for booking {}; "
                    + "a replay will resolve through the unique index instead",
                    operation, key, bookingId, ex);
            return false;
        }
    }

    private static String redisKey(Operation operation, String key) {
        return KEY_PREFIX + operation.segment + ":" + key;
    }
}
