package com.bookmyseat.gateway.service;

import com.bookmyseat.gateway.config.BucketPolicy;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Clock;
import java.util.List;

/**
 * Token-bucket rate limiter, ported from rate-limiter-demo
 * (github.com/Arshad-327/rate-limiter, commit 6f30c29, TokenBucketRateLimiter).
 *
 * <p>The read-check-write is a single Lua script run via EVAL. Redis executes scripts
 * atomically - no other command (including another EVAL of this same script) can interleave
 * between the HMGET and the HSET - so the lost-update race of a read-then-write token bucket
 * cannot happen: under concurrency, exactly {@code capacity} requests are ever allowed through
 * for a client whose bucket started full.
 *
 * <p>Token bucket suits a gateway: it absorbs a legitimate burst, such as one page load firing
 * several requests, while holding the sustained rate down.
 *
 * <h2>What carried over, and what changed</h2>
 * <b>The Lua script carries over byte-for-byte.</b> {@link #SCRIPT} is the original text
 * block unchanged; TokenBucketScriptFingerprintTest pins its SHA-1 to the public commit's, and
 * the Redis-backed test checks Redis itself knows the script by that SHA-1. The algorithm and
 * its atomicity are exactly the original's.
 *
 * <p><b>The surrounding Java changed, in these ways:</b>
 * <ol>
 *   <li><b>Blocking to reactive.</b> StringRedisTemplate became ReactiveStringRedisTemplate, and
 *       the result is a {@code Mono}. This runs on the Netty event loop, where a blocking call
 *       raises no error and silently stalls every request sharing the thread. The reactive
 *       template alone was NOT enough: its connection setup still blocks, so the call is
 *       subscribed on boundedElastic - see the comment in {@link #checkLimit}.</li>
 *   <li><b>{@code Instant.now()} became the injected Clock</b> (CLAUDE.md Timekeeping), so tests
 *       can pin and advance time.</li>
 *   <li><b>A null or malformed reply now lets the request through.</b> The original computed
 *       {@code allowed = result != null && ...}, so a missing reply DENIED - it failed closed.
 *       Here that case is an error the filter treats as fail-open, like any Redis failure. This
 *       is a deliberate deviation from the original Java, not from the script.</li>
 *   <li><b>Limits are passed in per call</b> as a {@link BucketPolicy}, not the constants
 *       BUCKET_SIZE = 20 and REFILL_TOKENS_PER_SECOND = 1.0. The script already took both as
 *       ARGV, so it needed nothing. The key TTL (ARGV[4]) also comes from the policy instead of
 *       a fixed 60 seconds - see {@link BucketPolicy#keyTtlSeconds()}.</li>
 *   <li><b>The key gains the policy name:</b> {@code ratelimit:token-bucket:{policy}:{client}},
 *       still under the original prefix. Two policies sharing one key would corrupt each other:
 *       the script's {@code math.min(bucketSize, ...)} would clamp the shared hash to the smaller
 *       bucket on every call, so demo traffic would quietly drain the general budget.</li>
 * </ol>
 *
 * <h2>Dropped deliberately</h2>
 * <ul>
 *   <li><b>RateLimitEventPublisher and the STOMP dashboard.</b> Live observability of decisions
 *       was that project's purpose. It is not this one's.</li>
 *   <li><b>The X-RateLimit-Strategy header</b>, and with it the RateLimiter strategy interface.
 *       It let a caller choose which algorithm limits them - so they choose the weakest. Right
 *       for an app built to compare algorithms live; a hole in front of a real system.</li>
 *   <li><b>The X-Client-Tier header and its multiplier</b> (ClientTierConfig, RateLimiterMath).
 *       A tier a client declares for itself is not a tier. If tiers return, the value must come
 *       from the validated token - and the JWT filter runs AFTER the rate limiter, so it cannot.</li>
 * </ul>
 * The last two are the same lesson as X-User-Id, one layer down: a limit a client can raise by
 * asking is not a limit.
 */
@Service
public class TokenBucketRateLimiter {

    static final String KEY_PREFIX = "ratelimit:token-bucket:";

    // Returns {allowed, tokensRemaining, retryAfterSeconds} as a Lua table - Redis converts
    // numbers in a table reply to integers, so tokensRemaining is rounded here rather than
    // truncated silently.
    //
    // BYTE-FOR-BYTE the original. Do not reformat, re-indent or "tidy" this text block: its
    // SHA-1 is pinned by TokenBucketScriptFingerprintTest.
    static final String SCRIPT = """
            local key = KEYS[1]
            local bucketSize = tonumber(ARGV[1])
            local refillRate = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local ttl = tonumber(ARGV[4])

            local state = redis.call('HMGET', key, 'tokens', 'lastRefill')
            local tokens = tonumber(state[1])
            local lastRefill = tonumber(state[2])

            if tokens == nil or lastRefill == nil then
                tokens = bucketSize
                lastRefill = now
            end

            local elapsedSeconds = math.max(0, now - lastRefill) / 1000.0
            tokens = math.min(bucketSize, tokens + elapsedSeconds * refillRate)

            local allowed = 0
            local retryAfterSeconds = 0
            if tokens >= 1 then
                allowed = 1
                tokens = tokens - 1
            else
                retryAfterSeconds = math.ceil((1 - tokens) / refillRate)
            end

            redis.call('HSET', key, 'tokens', tostring(tokens), 'lastRefill', tostring(now))
            redis.call('EXPIRE', key, ttl)

            return {allowed, math.floor(tokens + 0.5), retryAfterSeconds}
            """;

    private final ReactiveStringRedisTemplate redisTemplate;
    @SuppressWarnings("rawtypes")
    private final DefaultRedisScript<List> script;
    private final Clock clock;

    public TokenBucketRateLimiter(ReactiveStringRedisTemplate redisTemplate, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.script = new DefaultRedisScript<>(SCRIPT, List.class);
        this.clock = clock;
    }

    /**
     * Spends one token from this client's bucket under this policy, if it has one.
     *
     * @return the decision; or an error if Redis failed or replied with anything other than the
     *         script's three-number table. The caller decides what an error means - see
     *         RateLimitFilter, which fails open.
     */
    public Mono<RateLimitDecision> checkLimit(String clientId, BucketPolicy policy) {
        int bucketSize = policy.capacity();
        String key = KEY_PREFIX + policy.name() + ":" + clientId;
        long now = clock.millis();

        return redisTemplate.execute(
                        script,
                        List.of(key),
                        List.of(
                                String.valueOf(bucketSize),
                                String.valueOf(policy.refillPerSecond()),
                                String.valueOf(now),
                                String.valueOf(policy.keyTtlSeconds())))
                // NOT OPTIONAL, and not the redundancy it looks like on a reactive template.
                // Spring Data Redis obtains its shared Lettuce connection with a BLOCKING wait
                // the first time a reactive command needs it, and tries again on every call while
                // Redis is unreachable or hung. Subscribed on the caller's thread - a Netty event
                // loop, in RateLimitFilter - that wait would stall every request on the loop, and
                // RateLimitFilter's timeout could not cut it short, because the thread it would
                // interrupt is the one that is stuck. boundedElastic is Reactor's scheduler for
                // exactly this: the wait happens there, the event loop is released at once, and
                // the timeout works. Proven by RateLimiterNonBlockingTest, which failed without
                // this line.
                .subscribeOn(Schedulers.boundedElastic())
                .collectList()
                .flatMap(reply -> toDecision(reply, bucketSize));
    }

    /**
     * The original's mapping from the script's reply, unchanged apart from what a missing reply
     * means (see the class javadoc, change 3).
     */
    private static Mono<RateLimitDecision> toDecision(List<?> reply, int bucketSize) {
        // The reactive executor may emit the table as one List element or as its three numbers.
        List<?> result = reply.size() == 1 && reply.get(0) instanceof List<?> table ? table : reply;

        if (result.size() != 3 || !result.stream().allMatch(Number.class::isInstance)) {
            return Mono.error(new IllegalStateException(
                    "token-bucket script returned an unexpected reply: " + reply));
        }

        boolean allowed = ((Number) result.get(0)).longValue() == 1L;
        long tokensRemaining = ((Number) result.get(1)).longValue();
        long retryAfterSeconds = ((Number) result.get(2)).longValue();
        long currentCount = bucketSize - tokensRemaining;

        return Mono.just(new RateLimitDecision(allowed, currentCount, bucketSize, retryAfterSeconds));
    }
}
