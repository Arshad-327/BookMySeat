package com.bookmyseat.gateway;

import com.bookmyseat.gateway.config.BucketPolicy;
import com.bookmyseat.gateway.service.RateLimitDecision;
import com.bookmyseat.gateway.service.TokenBucketRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RateLimitFilter and the ported token bucket against a real Redis, with the application's own
 * policies from application.yml: default burst 40 / 2 per second, demo burst 20 / 1 per 3 seconds.
 *
 * <p>The Clock is a {@link MutableClock}: pinned, and moved only when a test says so. A bucket
 * therefore refills exactly when the test advances time, never because the test ran slowly.
 *
 * <p>trusted-proxy-hops is 0, as it is locally, so every request's client is the socket address.
 * TrustedProxyRateLimitTest covers the other setting.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RateLimitFilterTest {

    private static final Instant START = Instant.parse("2026-09-14T10:00:00Z");
    private static final MutableClock CLOCK = new MutableClock(START);
    private static final int DEAD_PORT = unusedPort();

    private static final String DEMO = "/api/demo/spam";
    private static final int DEMO_CAPACITY = 20;
    private static final int DEFAULT_CAPACITY = 40;

    @TestConfiguration
    static class Clocks {
        @Bean
        @Primary
        Clock mutableClock() {
            return CLOCK;
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String nowhere = "http://localhost:" + DEAD_PORT;
        registry.add("app.services.auth-service", () -> nowhere);
        registry.add("app.services.event-service", () -> nowhere);
        registry.add("app.services.booking-service", () -> nowhere);
        registry.add("app.jwt.secret", () -> TestTokens.SECRET);
        registry.add("spring.data.redis.host", RedisContainerSupport::host);
        registry.add("spring.data.redis.port", RedisContainerSupport::port);
        registry.add("management.server.port", () -> "0");
    }

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private TokenBucketRateLimiter limiter;

    @BeforeEach
    void reset() {
        RedisContainerSupport.cli("FLUSHALL");
        CLOCK.set(START);
    }

    @Test
    @DisplayName("under the limit passes, and X-RateLimit-Remaining counts the burst down to 0")
    void underTheLimitPassesAndCountsDown() {
        for (int i = 1; i <= DEMO_CAPACITY; i++) {
            EntityExchangeResult<byte[]> result = get(DEMO, null);

            assertThat(result.getStatus().value()).as("request %d", i).isEqualTo(200);
            assertThat(result.getResponseHeaders().getFirst("X-RateLimit-Remaining"))
                    .as("request %d", i).isEqualTo(String.valueOf(DEMO_CAPACITY - i));
        }
    }

    @Test
    @DisplayName("over the limit is 429 with Retry-After and X-RateLimit-Remaining, in the standard error shape")
    void overTheLimitIsRejected() throws Exception {
        // One millisecond apart, like a tight curl loop, rather than all at one instant - see
        // the Retry-After note below.
        for (int i = 1; i <= DEMO_CAPACITY; i++) {
            assertThat(get(DEMO, null).getStatus().value()).isEqualTo(200);
            CLOCK.advance(Duration.ofMillis(1));
        }

        EntityExchangeResult<byte[]> rejected = get(DEMO, null);

        assertThat(rejected.getStatus().value()).isEqualTo(429);
        // Demo refill is one token per 3 seconds. With the 20 requests 1 ms apart, about 0.007 of
        // a token has refilled, so the next whole token is ceil(0.993 x 3) = 3 seconds away.
        //
        // At the same millisecond it would be 4, not 3: the refill rate passed to the script is
        // the double 0.3333333333333333, a hair under 1/3, so 1 / rate is 3.0000000000000004 and
        // the script's math.ceil rounds it up. Correct for the script as written, and not
        // something to "fix" by nudging the rate.
        assertThat(rejected.getResponseHeaders().getFirst("Retry-After")).isEqualTo("3");
        assertThat(rejected.getResponseHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("0");

        assertThat(TestTokens.assertStandardErrorShape(rejected.getResponseBodyContent(), 429, DEMO)
                .get("timestamp").asText()).isEqualTo(CLOCK.instant().toString());
    }

    @Test
    @DisplayName("ORDERING: an over-limit request with NO token gets 429, not 401 - the limiter runs before the JWT filter")
    void limiterRunsBeforeAuthentication() throws Exception {
        String protectedPath = "/api/bookings/1";

        // The default budget is spent by requests the JWT filter refuses: each one passed the
        // limiter first, which is why each one costs a token.
        for (int i = 1; i <= DEFAULT_CAPACITY; i++) {
            assertThat(get(protectedPath, null).getStatus().value()).as("request %d", i).isEqualTo(401);
        }

        EntityExchangeResult<byte[]> overLimit = get(protectedPath, null);

        // If the JWT filter ran first, this would be one more 401: authentication would refuse
        // it before the limiter was ever asked, and no token-less flood could exhaust a bucket.
        assertThat(overLimit.getStatus().value()).isEqualTo(429);
        TestTokens.assertStandardErrorShape(overLimit.getResponseBodyContent(), 429, protectedPath);
    }

    @Test
    @DisplayName("a FORGED X-Forwarded-For does not split the budget when no proxy is trusted")
    void forgedForwardedForDoesNotSplitTheBudget() {
        for (int i = 1; i <= DEMO_CAPACITY; i++) {
            assertThat(get(DEMO, "203.0.113." + i).getStatus().value()).isEqualTo(200);
        }

        // A brand-new invented address. With hops = 0 the gateway ignores the header entirely,
        // so this is still the same socket client, with the same empty bucket.
        EntityExchangeResult<byte[]> forged = get(DEMO, "198.51.100.77");

        assertThat(forged.getStatus().value()).isEqualTo(429);
        assertThat(keys("ratelimit:token-bucket:demo:*")).hasSize(1);
    }

    @Test
    @DisplayName("each route policy has its own bucket and key: spending the demo budget leaves the default one alone")
    void policiesDoNotShareABucket() {
        for (int i = 1; i <= DEMO_CAPACITY; i++) {
            get(DEMO, null);
        }
        assertThat(get(DEMO, null).getStatus().value()).isEqualTo(429);

        // Same client, default policy: a full bucket of its own. (5xx: the downstream is absent.)
        EntityExchangeResult<byte[]> other = get("/api/events", null);
        assertThat(other.getStatus().value()).isNotEqualTo(429);

        List<String> demoKeys = keys("ratelimit:token-bucket:demo:*");
        List<String> defaultKeys = keys("ratelimit:token-bucket:default:*");
        assertThat(demoKeys).hasSize(1);
        assertThat(defaultKeys).hasSize(1);

        // Both refill from empty within 60 seconds, so both keys live exactly the 60-second floor.
        assertThat(Long.parseLong(RedisContainerSupport.cli("TTL", demoKeys.get(0)))).isBetween(1L, 60L);
        assertThat(Long.parseLong(RedisContainerSupport.cli("TTL", defaultKeys.get(0)))).isBetween(1L, 60L);
    }

    @Test
    @DisplayName("a rejected client is allowed again once the injected clock has refilled one token, and only one")
    void bucketRefillsWithTheClock() {
        for (int i = 1; i <= DEMO_CAPACITY; i++) {
            get(DEMO, null);
        }
        assertThat(get(DEMO, null).getStatus().value()).isEqualTo(429);

        CLOCK.advance(Duration.ofSeconds(3));

        assertThat(get(DEMO, null).getStatus().value()).isEqualTo(200);
        assertThat(get(DEMO, null).getStatus().value()).isEqualTo(429);
    }

    @Test
    @DisplayName("a CORS preflight still succeeds for a client whose bucket is empty")
    void preflightSucceedsWhileRateLimited() {
        // This test first failed with 403 against a FULL bucket too: the demo route then had a
        // Method=GET predicate, which an OPTIONS preflight never matches. Not the limiter - but
        // a browser could not have called the demo endpoint at all. See application.yml.
        for (int i = 1; i <= DEMO_CAPACITY; i++) {
            get(DEMO, null);
        }
        assertThat(get(DEMO, null).getStatus().value()).isEqualTo(429);

        webTestClient.options().uri(DEMO)
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "GET")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:5173");
    }

    @Test
    @DisplayName("ATOMICITY: 40 concurrent checks against a full 20-token bucket allow exactly 20")
    void concurrentChecksAllowExactlyTheBucketSize() throws Exception {
        // Ported from rate-limiter-demo's TokenBucketConcurrencyTest (the atomic half). The clock
        // is pinned, so no token refills mid-burst: more than 20 approvals could only mean two
        // checks interleaved inside the script, which Redis's atomic EVAL rules out.
        BucketPolicy policy = new BucketPolicy("atomicity", 20, 60);
        int concurrent = 40;

        // The JUnit thread waits for the result through a future. It is not a Netty event-loop
        // thread, so waiting here stalls nothing; the 40 checks themselves run non-blocking.
        Long allowed = Flux.range(0, concurrent)
                .flatMap(i -> limiter.checkLimit("concurrency-test-client", policy), concurrent)
                .filter(RateLimitDecision::allowed)
                .count()
                .toFuture()
                .get(30, TimeUnit.SECONDS);

        assertThat(allowed).isEqualTo(20L);
    }

    @Test
    @DisplayName("Redis itself holds the token-bucket script under the public commit's SHA-1")
    void redisKnowsTheScriptByThePublicSha() {
        assertThat(get(DEMO, null).getStatus().value()).isEqualTo(200);

        // SCRIPT EXISTS answers from Redis's own script cache, keyed by the SHA-1 Redis computed
        // over the bytes it was sent. 1 means the script Redis ran is the public repo's, byte for
        // byte - checked by the server, not by this module.
        assertThat(RedisContainerSupport.cli("SCRIPT", "EXISTS", "79c9edb17b509dfd711ec525f35f122f3376fec3"))
                .isEqualTo("1");
    }

    private EntityExchangeResult<byte[]> get(String path, String forwardedFor) {
        WebTestClient.RequestHeadersSpec<?> request = webTestClient.get().uri(path);
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return request.exchange().expectBody().returnResult();
    }

    private static List<String> keys(String pattern) {
        String out = RedisContainerSupport.cli("--scan", "--pattern", pattern);
        return out.isBlank() ? List.of() : Arrays.stream(out.split("\\R")).map(String::trim).toList();
    }

    private static int unusedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException ex) {
            throw new UncheckedIOException("could not find a free port", ex);
        }
    }
}
