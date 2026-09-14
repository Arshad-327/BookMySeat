package com.bookmyseat.gateway.config;

import com.bookmyseat.gateway.exception.ErrorResponseWriter;
import com.bookmyseat.gateway.service.RateLimitDecision;
import com.bookmyseat.gateway.service.TokenBucketRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pre-authentication rate limiting at the edge: a token bucket per client IP, per route policy.
 *
 * <p>Runs only for requests a route matched. CORS preflights never reach it - the gateway's
 * handler mapping answers them before any global filter - so a browser's preflight still
 * succeeds for a client whose bucket is empty.
 *
 * <h2>Order: before authentication, deliberately</h2>
 * {@link #ORDER} is -200, ahead of JwtAuthenticationFilter at -100. A rate-limit check is one
 * Redis script call; verifying a JWT is an HMAC. Abusive traffic is refused before the gateway
 * spends CPU verifying signatures for it. In the other order a flood of junk tokens would cost a
 * signature check each. RateLimitFilterTest asserts this: an over-limit request with NO token
 * gets 429, not 401.
 *
 * <h2>Keyed on client IP - and why not on the user</h2>
 * Because this runs before authentication, there is no validated identity to key on. Reading
 * the token here would mean either trusting an unverified claim - a client could rotate a made-up
 * user id per request and never run out - or verifying the signature, which spends exactly the
 * CPU this ordering exists to save. So the key is the client IP.
 *
 * <p><b>The known limitation:</b> everyone behind one NAT shares one bucket. A campus or office
 * network is a single client here. That is inherent to pre-authentication limiting, and it is
 * why a production system uses <b>two tiers</b>: this cheap, coarse, pre-auth limit per IP to
 * shed floods; and a SECOND, tighter limiter per user, running AFTER the JWT filter, keyed on
 * the validated user id, precise enough to stop one account's abuse without penalising its
 * neighbours. Only the first tier exists today.
 *
 * <h2>Which IP: the socket address, unless proxies are configured as trusted</h2>
 * Behind a real load balancer the socket address is the balancer's, and the client's address
 * arrives in X-Forwarded-For. But X-Forwarded-For is a request header like any other: trusting
 * it unconditionally lets anyone dodge the limit by inventing a new value per request - the
 * same attack as spoofing X-User-Id, one layer down.
 *
 * <p>So {@code app.rate-limit.trusted-proxy-hops} decides. At 0 - the local default, where
 * clients connect to the gateway directly - X-Forwarded-For is ignored and the socket address
 * is the client. Set to N behind N trusted proxies, the client is the address the outermost
 * trusted proxy appended, taken from the right of X-Forwarded-For, so values a client prepends
 * itself are skipped (Spring Cloud Gateway's XForwardedRemoteAddressResolver).
 *
 * <h2>Per-route budgets, because endpoints are not alike</h2>
 * Different endpoints cost different amounts to serve and attract different abuse. A seat map
 * polled every few seconds by an honest visitor is 15-20 requests a minute; a login endpoint is
 * a password-guessing target; a demo endpoint exists to be hammered. A single global number
 * either throttles honest traffic or fails to stop a script. That is why real limiters are per
 * route: {@code app.rate-limit.routes} matches a path to its own budget, with a generous default
 * for everything else. Each policy has its own Redis key - see TokenBucketRateLimiter.
 *
 * <h2>Redis failure posture: this FAILS OPEN</h2>
 * If Redis is unreachable, slow past {@code app.rate-limit.redis-timeout}, or replies with
 * anything unexpected, the request is let through and a warning is logged. The limit is
 * advisory: without it, a client briefly exceeds its quota, and the damage is bounded and ends
 * when Redis returns. Refusing all traffic instead would turn a Redis outage into a total
 * outage, the protection causing a worse failure than the thing it protects against.
 *
 * <p>This is the opposite of the seat hold, which fails closed, and the same as idempotency,
 * which fails open. All three use Redis. <b>The posture follows from what the mechanism
 * guarantees, not from which datastore it uses:</b> fail closed only where the unavailable
 * mechanism IS the guarantee, fail open where it is advisory or an optimisation in front of a
 * guarantee that still holds. booking-service's SeatHoldService and IdempotencyService state the
 * same rule from their side.
 *
 * <p>The timeout is what makes failing open real. Without it, a dead Redis would hold every
 * request for Lettuce's own timeout before letting it through: an availability outage wearing a
 * fail-open label.
 */
@Component
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitFilter implements GlobalFilter, Ordered {

    /** Before JwtAuthenticationFilter.ORDER (-100). See the class javadoc. */
    public static final int ORDER = -200;

    public static final String REMAINING_HEADER = "X-RateLimit-Remaining";

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final TokenBucketRateLimiter limiter;
    private final ErrorResponseWriter errorWriter;
    private final RateLimitProperties properties;
    private final List<CompiledRoute> routes;
    private final XForwardedRemoteAddressResolver forwardedResolver;

    private record CompiledRoute(PathPattern pattern, BucketPolicy policy) {
    }

    public RateLimitFilter(TokenBucketRateLimiter limiter, ErrorResponseWriter errorWriter,
                           RateLimitProperties properties) {
        this.limiter = limiter;
        this.errorWriter = errorWriter;
        this.properties = properties;
        this.routes = properties.routes().stream()
                .map(route -> new CompiledRoute(PathPatternParser.defaultInstance.parse(route.pattern()), route.policy()))
                .toList();
        this.forwardedResolver = properties.trustedProxyHops() > 0
                ? XForwardedRemoteAddressResolver.maxTrustedIndex(properties.trustedProxyHops())
                : null;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String client = clientIp(exchange);
        BucketPolicy policy = policyFor(exchange.getRequest().getPath().pathWithinApplication());

        // THE TIMEOUT CAN MAKE THE COUNTER AND THE RESPONSE DISAGREE, and that is inherent.
        // .timeout stops the gateway WAITING; it cannot recall the script. A check that times out
        // may already have reached Redis and spent a token, while this request is let through as
        // though it was never counted. The client's bucket is then lower than the responses it
        // saw imply. Nothing can close that gap from here: the check is not idempotent - running
        // it spends a token - so there is no safe way to cancel or retry it once sent. Putting a
        // deadline on a non-idempotent operation always leaves "did it happen?" unanswered at the
        // deadline. It is accepted because the error is small and in the safe direction for the
        // gateway, and because the alternative - waiting for Redis however long it takes - is
        // the outage fail-open exists to prevent. RateLimiterWarmUp keeps it off cold start.
        return limiter.checkLimit(client, policy)
                .timeout(properties.redisTimeout())
                .map(Optional::of)
                // Fail open. Only the limiter's own Mono is guarded here: everything downstream
                // in chain.filter is outside this, so a downstream error is never mistaken for a
                // Redis failure and swallowed.
                .onErrorResume(ex -> {
                    log.warn("rate limit check failed for {} under policy {}; letting the request through ({}: {})",
                            client, policy.name(), ex.getClass().getSimpleName(), ex.getMessage());
                    return Mono.just(Optional.empty());
                })
                .flatMap(decision -> decision
                        .map(d -> apply(exchange, chain, d))
                        .orElseGet(() -> chain.filter(exchange)));
    }

    private Mono<Void> apply(ServerWebExchange exchange, GatewayFilterChain chain, RateLimitDecision decision) {
        if (decision.allowed()) {
            // The script rounds tokens remaining to the nearest whole token, so this can read one
            // higher than the whole tokens left. The script is carried over unchanged, so the
            // header is reported as it rounds it.
            long remaining = decision.limit() - decision.currentCount();
            exchange.getResponse().getHeaders().set(REMAINING_HEADER, String.valueOf(remaining));
            return chain.filter(exchange);
        }

        // A denial means less than one whole token was left, whatever the rounded count says,
        // so remaining is 0 here unconditionally.
        return errorWriter.write(exchange, HttpStatus.TOO_MANY_REQUESTS,
                "Too many requests; retry after " + decision.retryAfterSeconds() + " second(s)",
                Map.of(
                        HttpHeaders.RETRY_AFTER, String.valueOf(decision.retryAfterSeconds()),
                        REMAINING_HEADER, "0"));
    }

    private BucketPolicy policyFor(PathContainer path) {
        for (CompiledRoute route : routes) {
            if (route.pattern().matches(path)) {
                return route.policy();
            }
        }
        return properties.defaultPolicy();
    }

    private String clientIp(ServerWebExchange exchange) {
        InetSocketAddress address = forwardedResolver != null
                ? forwardedResolver.resolve(exchange)
                : exchange.getRequest().getRemoteAddress();
        if (address == null) {
            return "unknown";
        }
        return address.getAddress() != null ? address.getAddress().getHostAddress() : address.getHostString();
    }
}
