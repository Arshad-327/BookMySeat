package com.bookmyseat.booking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Binds app.idempotency.*.
 *
 * <p>ttl is how long a used idempotency key is remembered in Redis. Twenty-four
 * hours: long enough to cover any client retry worth calling a retry - a mobile
 * app resuming after a lost connection, a queued job draining - and short enough
 * that the keyspace does not grow without bound.
 *
 * <p><b>This TTL is not the guarantee, and it is safe for it to lapse.</b> When a
 * key expires from Redis, a replay stops taking the fast path and falls through to
 * the insert, where the unique index on bookings.idempotency_key refuses it and the
 * original booking is returned instead. Expiry costs a round trip, not correctness -
 * which is exactly why this value can be chosen for housekeeping reasons rather than
 * for safety ones. Contrast app.seat-hold.ttl, where the expiry IS the semantics:
 * a lapsed seat hold genuinely releases the seat.
 *
 * <p>Duration, not a raw int, so the yaml reads {@code ttl: 86400s} and the unit is
 * part of the value. Redis wants whole seconds, so
 * {@link com.bookmyseat.booking.service.IdempotencyService} converts once at the
 * call site.
 */
@ConfigurationProperties(prefix = "app.idempotency")
public record IdempotencyProperties(Duration ttl) {
}
