package com.bookmyseat.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RateLimiterWarmUp reaches Redis during startup, before any request is sent.
 *
 * <p>The evidence is in Redis itself: the warm-up's own throwaway key exists, with its expiry,
 * by the time the context has started and this test runs, and no request has been made. The
 * extra property only forces a fresh application context, so the warm-up genuinely runs for
 * this class rather than being inherited from a cached context that an earlier test class's
 * FLUSHALL has since wiped.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "test.fresh-context-for=RateLimiterWarmUpTest")
class RateLimiterWarmUpTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> TestTokens.SECRET);
        registry.add("spring.data.redis.host", RedisContainerSupport::host);
        registry.add("spring.data.redis.port", RedisContainerSupport::port);
        registry.add("management.server.port", () -> "0");
    }

    @Test
    @DisplayName("startup has already opened the Redis connection and run the script: the warm-up key exists before any request")
    void warmUpRanAgainstRedisAtStartup() {
        String key = "ratelimit:token-bucket:warmup:gateway-startup";

        assertThat(RedisContainerSupport.cli("EXISTS", key)).isEqualTo("1");
        assertThat(Long.parseLong(RedisContainerSupport.cli("TTL", key))).isBetween(1L, 60L);
        assertThat(RedisContainerSupport.cli("SCRIPT", "EXISTS", "79c9edb17b509dfd711ec525f35f122f3376fec3"))
                .isEqualTo("1");
    }
}
