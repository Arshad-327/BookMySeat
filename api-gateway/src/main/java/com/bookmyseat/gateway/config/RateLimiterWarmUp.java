package com.bookmyseat.gateway.config;

import com.bookmyseat.gateway.service.TokenBucketRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Pays the rate limiter's cold-start cost at boot instead of on the first requests.
 *
 * <p><b>The cost being moved.</b> The first rate-limit check after startup opens Lettuce's shared
 * Redis connection and sends the token-bucket script for the first time. Observed live in P4.3:
 * that took longer than app.rate-limit.redis-timeout (100ms) for the first three requests, which
 * therefore failed open and went through uncounted. Nothing was wrong - the limit was simply
 * not being applied to the first users of a freshly started gateway. This is not hiding a bug;
 * it is moving a known, one-off cost off the request path.
 *
 * <p><b>How.</b> One real check, through the same {@link TokenBucketRateLimiter#checkLimit} path a
 * request takes, so the connection, the script cache and the code path are all warm. It uses its
 * own policy name, so it writes one throwaway key, {@code ratelimit:token-bucket:warmup:gateway-startup},
 * which touches no client's budget and expires on its own after 60 seconds.
 *
 * <p>It runs as an ApplicationRunner on the main thread - never a Netty event loop - and waits
 * a bounded time. A Redis that is down or slow at boot must not stop the gateway starting: the
 * limiter fails open, so the gateway is still correct without Redis, and a failed warm-up is
 * logged and ignored. Requests then pay the connection cost themselves, as before.
 */
@Component
public class RateLimiterWarmUp implements ApplicationRunner {

    static final String CLIENT = "gateway-startup";
    static final BucketPolicy POLICY = new BucketPolicy("warmup", 1, 60);

    /** Generous next to the per-request timeout: this is paid once, before traffic matters. */
    private static final Duration MAX_WAIT = Duration.ofSeconds(5);

    private static final Logger log = LoggerFactory.getLogger(RateLimiterWarmUp.class);

    private final TokenBucketRateLimiter limiter;

    public RateLimiterWarmUp(TokenBucketRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            // Waiting is fine here: this is the main thread at startup, not an event loop.
            limiter.checkLimit(CLIENT, POLICY)
                    .toFuture()
                    .get(MAX_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            log.info("rate limiter warmed up: Redis connection open and token-bucket script loaded");
        } catch (TimeoutException | ExecutionException ex) {
            log.warn("rate limiter warm-up did not complete ({}); the gateway starts anyway and rate "
                    + "limiting fails open until Redis answers", ex.getClass().getSimpleName());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("rate limiter warm-up interrupted; the gateway starts anyway");
        }
    }
}
