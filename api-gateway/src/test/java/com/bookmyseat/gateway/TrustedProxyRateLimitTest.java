package com.bookmyseat.gateway;

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
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * trusted-proxy-hops = 1: the gateway sits behind one trusted proxy, so the client is the
 * address that proxy appended - the RIGHTMOST X-Forwarded-For value.
 *
 * <p>The companion to RateLimitFilterTest's forged-header test. There, with no trusted proxy, a
 * new X-Forwarded-For value buys nothing. Here, the trusted proxy's value does separate clients -
 * but a value the client prepends itself still buys nothing, because only the rightmost entry
 * is believed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "app.rate-limit.trusted-proxy-hops=1")
class TrustedProxyRateLimitTest {

    private static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-09-14T10:00:00Z"));
    private static final String DEMO = "/api/demo/spam";

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
        registry.add("app.jwt.secret", () -> TestTokens.SECRET);
        registry.add("spring.data.redis.host", RedisContainerSupport::host);
        registry.add("spring.data.redis.port", RedisContainerSupport::port);
        registry.add("management.server.port", () -> "0");
    }

    @Autowired
    private WebTestClient webTestClient;

    @BeforeEach
    void reset() {
        RedisContainerSupport.cli("FLUSHALL");
    }

    @Test
    @DisplayName("behind a trusted proxy, two clients get two budgets - and prepending a fake address escapes nothing")
    void trustedProxyAddressSeparatesClients() {
        for (int i = 1; i <= 20; i++) {
            assertThat(status("203.0.113.1")).as("client A request %d", i).isEqualTo(200);
        }
        assertThat(status("203.0.113.1")).as("client A, over its budget").isEqualTo(429);

        // A different client, as reported by the trusted proxy: a full bucket of its own.
        assertThat(status("203.0.113.2")).as("client B").isEqualTo(200);

        // Client A again, having prepended an invented address. The proxy appends A's real
        // address on the right, and only that entry is trusted.
        assertThat(status("198.51.100.9, 203.0.113.1")).as("client A with a prepended fake").isEqualTo(429);
    }

    private int status(String forwardedFor) {
        return webTestClient.get().uri(DEMO)
                .header("X-Forwarded-For", forwardedFor)
                .exchange()
                .expectBody().returnResult()
                .getStatus().value();
    }
}
