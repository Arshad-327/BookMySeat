package com.bookmyseat.gateway.config;

import com.bookmyseat.gateway.exception.ErrorResponseWriter;
import com.bookmyseat.gateway.service.AccessTokenVerifier;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Authenticates routed requests at the edge and replaces any client-claimed identity with
 * the one the access token proves.
 *
 * <p>Runs only for requests a route matched. An unrouted path such as /api/internal/** is
 * a 404 before it gets here.
 *
 * <h2>For every request, public or not: strip first</h2>
 * Every inbound header whose name starts with {@code X-User-}, in any letter case, is
 * removed before anything else happens. HTTP header names are case-insensitive, so
 * {@code x-user-id} and {@code X-USER-ID} are the same header to every downstream service.
 * A public path is stripped too: a public path that forwards a spoofed header is still
 * forwarding a spoofed header.
 *
 * <h2>Public paths: no token needed, and none looked at</h2>
 * /api/auth/** (register, login, refresh, logout, and /me, which auth-service
 * authenticates itself), plus the catalogue reads GET /api/events/** and
 * GET /api/shows/{id}/seats, plus GET /api/demo/** (the rate-limit demonstration endpoint).
 * A visitor must be able to browse and open a seat map before
 * logging in, and the frontend polls that seat map. So a token on a public path is not
 * even parsed: an expired one must not turn a poll into a 401.
 *
 * <p>The catalogue is public for GET only. A write added under /api/events later is
 * protected by default, not open by accident.
 *
 * <h2>Everything else: protected, by default</h2>
 * /api/bookings/** and /api/admin/** today, and any routed path added later that nobody
 * classified. A missing or invalid token is a 401. A valid one sets X-User-Id from the
 * token's subject and X-User-Role from its role claim. Authorization is forwarded
 * unchanged.
 *
 * <h2>Why the downstream services may trust these headers</h2>
 * booking-service and event-service take X-User-Id and X-User-Role at face value and do
 * no token checking of their own. That is only safe because they are reachable on the
 * internal network alone, so every request carrying those headers has come through this
 * filter. Here that rests on Docker networking and on 8081-8083 not being exposed
 * publicly. Production would enforce it with network policy or mTLS, so a service refuses
 * any caller that is not the gateway. Without that, anyone who can reach a service
 * directly can still claim to be anyone.
 */
@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    /**
     * Where this filter runs. Lower runs earlier.
     *
     * <p>{@link RateLimitFilter} runs BEFORE this, at {@link RateLimitFilter#ORDER} (-200), so
     * abusive traffic is refused by a cheap Redis check before the gateway spends CPU
     * verifying signatures for it. RateLimitFilterTest asserts that order. This must also
     * stay below the gateway's own routing filters (NettyWriteResponseFilter is -1, the
     * routing filter is Integer.MAX_VALUE) so a request is authenticated before it is
     * forwarded anywhere.
     */
    public static final int ORDER = -100;

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_ROLE_HEADER = "X-User-Role";

    /** Compared lower-case, against lower-cased header names. */
    private static final String USER_HEADER_PREFIX = "x-user-";

    private static final String BEARER_SCHEME = "bearer ";

    private static final PathPatternParser PARSER = PathPatternParser.defaultInstance;

    /** Public for any method: auth-service owns the authentication of its own endpoints. */
    private static final List<PathPattern> PUBLIC_ANY_METHOD = List.of(PARSER.parse("/api/auth/**"));

    /** Public for reads only. */
    private static final List<PathPattern> PUBLIC_READS = List.of(
            PARSER.parse("/api/events/**"),
            PARSER.parse("/api/shows/*/seats"),
            // The rate-limit demonstration endpoint. Unauthenticated so a 429 can be shown
            // with a bare curl loop; it is still rate limited, by RateLimitFilter.
            PARSER.parse("/api/demo/**"));

    private static final Set<HttpMethod> READ_METHODS = Set.of(HttpMethod.GET, HttpMethod.HEAD);

    private final AccessTokenVerifier verifier;
    private final ErrorResponseWriter errorWriter;

    public JwtAuthenticationFilter(AccessTokenVerifier verifier, ErrorResponseWriter errorWriter) {
        this.verifier = verifier;
        this.errorWriter = errorWriter;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest stripped = exchange.getRequest().mutate()
                .headers(JwtAuthenticationFilter::removeUserHeaders)
                .build();

        if (isPublic(stripped)) {
            return chain.filter(exchange.mutate().request(stripped).build());
        }

        String token = bearerToken(stripped);
        if (token == null) {
            return errorWriter.write(exchange, HttpStatus.UNAUTHORIZED,
                    "Authentication is required", Map.of(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
        }

        return verifier.verify(token)
                .map(user -> chain.filter(exchange.mutate()
                        .request(stripped.mutate()
                                .headers(headers -> {
                                    headers.set(USER_ID_HEADER, user.userId());
                                    headers.set(USER_ROLE_HEADER, user.role());
                                })
                                .build())
                        .build()))
                .orElseGet(() -> errorWriter.write(exchange, HttpStatus.UNAUTHORIZED,
                        "Access token is invalid or expired",
                        Map.of(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"")));
    }

    /**
     * Removes every X-User-* header by name, whatever its case. Names are collected first
     * and removed after, so the header map is never modified while being iterated.
     */
    private static void removeUserHeaders(HttpHeaders headers) {
        List<String> doomed = new ArrayList<>();
        for (String name : headers.keySet()) {
            if (name.toLowerCase(Locale.ROOT).startsWith(USER_HEADER_PREFIX)) {
                doomed.add(name);
            }
        }
        doomed.forEach(headers::remove);
    }

    private static boolean isPublic(ServerHttpRequest request) {
        var path = request.getPath().pathWithinApplication();
        if (PUBLIC_ANY_METHOD.stream().anyMatch(pattern -> pattern.matches(path))) {
            return true;
        }
        return READ_METHODS.contains(request.getMethod())
                && PUBLIC_READS.stream().anyMatch(pattern -> pattern.matches(path));
    }

    /**
     * The token from "Authorization: Bearer ...", or null. The scheme name is matched
     * case-insensitively, as RFC 7235 requires.
     */
    private static String bearerToken(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header == null || header.length() <= BEARER_SCHEME.length()
                || !header.regionMatches(true, 0, BEARER_SCHEME, 0, BEARER_SCHEME.length())) {
            return null;
        }
        String token = header.substring(BEARER_SCHEME.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
