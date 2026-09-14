package com.bookmyseat.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * app.rate-limit.* - see application.yml for the values and their reasoning.
 *
 * @param redisTimeout     the most one rate-limit check may wait on Redis before the request
 *                         is let through. Fail open is meaningless without it: a dead Redis
 *                         would stall every request for Lettuce's default timeout instead.
 * @param trustedProxyHops how many proxies in front of the gateway are trusted to append to
 *                         X-Forwarded-For. 0 means none: the socket address is the client.
 * @param defaultPolicy    the budget for any routed path no route policy matches
 * @param routes           per-path budgets, first match wins
 */
@ConfigurationProperties("app.rate-limit")
public record RateLimitProperties(
        Duration redisTimeout,
        int trustedProxyHops,
        BucketPolicy defaultPolicy,
        List<RoutePolicy> routes) {

    /** A path pattern and the budget for requests matching it. */
    public record RoutePolicy(String pattern, BucketPolicy policy) {
    }

    public RateLimitProperties {
        if (redisTimeout == null || redisTimeout.isNegative() || redisTimeout.isZero()) {
            throw new IllegalArgumentException("app.rate-limit.redis-timeout must be positive");
        }
        if (trustedProxyHops < 0) {
            throw new IllegalArgumentException("app.rate-limit.trusted-proxy-hops must not be negative");
        }
        if (defaultPolicy == null) {
            throw new IllegalArgumentException("app.rate-limit.default-policy is required");
        }
        routes = routes == null ? List.of() : List.copyOf(routes);
    }
}
