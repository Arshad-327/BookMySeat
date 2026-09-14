package com.bookmyseat.gateway.service;

/**
 * Result of a rate-limit check. Carried over from rate-limiter-demo (github.com/Arshad-327/
 * rate-limiter, 6f30c29) with its fields unchanged.
 *
 * <p>For the token bucket, {@code currentCount} is "tokens consumed out of the bucket" and
 * {@code limit} is the bucket size, so it always means "how much of {@code limit} has been
 * used so far."
 *
 * <p>{@code retryAfterSeconds} is only meaningful when {@code allowed} is false: how long the
 * caller should wait before the next token refills.
 */
public record RateLimitDecision(boolean allowed, long currentCount, int limit, long retryAfterSeconds) {
}
