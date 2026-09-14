package com.bookmyseat.gateway;

import com.bookmyseat.gateway.config.BucketPolicy;
import com.bookmyseat.gateway.service.RateLimitDecision;
import com.bookmyseat.gateway.service.TokenBucketRateLimiter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Subscribing to a rate-limit check must never block the thread that subscribes.
 *
 * <p>In RateLimitFilter that thread is a Netty event loop. A blocking call there raises no
 * error: it silently stalls every request sharing the thread, and a laptop with one user never
 * shows it. So this is asserted, against a Redis that accepts connections and never answers -
 * the case where connection setup takes longest.
 *
 * <p>The assertion is about ORDER, not time, so it does not depend on machine speed. A
 * non-blocking check returns from {@code subscribe()} at once, and its result arrives later. A
 * check that blocks while subscribing cannot return from {@code subscribe()} until it has already
 * failed - so its result is recorded first.
 */
// RANDOM_PORT rather than NONE: Spring Cloud Gateway's auto-configuration needs the reactive
// web server's ServerProperties and refuses to start without them.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RateLimiterNonBlockingTest {

    private static final RateLimitFailOpenTest.HungRedis HUNG = new RateLimitFailOpenTest.HungRedis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> TestTokens.SECRET);
        registry.add("spring.data.redis.port", HUNG::port);
        registry.add("management.server.port", () -> "0");
    }

    @AfterAll
    static void close() {
        HUNG.close();
    }

    @Autowired
    private TokenBucketRateLimiter limiter;

    @Test
    @DisplayName("subscribe() returns before the check's result arrives, even while Redis is hung")
    void subscribingDoesNotBlockTheCaller() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        CountDownLatch finished = new CountDownLatch(1);

        Mono<RateLimitDecision> check = limiter.checkLimit("non-blocking-client", new BucketPolicy("nonblocking", 20, 60));

        check.subscribe(
                decision -> {
                    events.add("result");
                    finished.countDown();
                },
                error -> {
                    events.add("result");
                    finished.countDown();
                },
                finished::countDown);
        events.add("subscribe returned");

        assertThat(finished.await(30, TimeUnit.SECONDS)).as("the check must finish, one way or another").isTrue();
        assertThat(events).containsExactly("subscribe returned", "result");
    }
}
