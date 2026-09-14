package com.bookmyseat.gateway.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Date;
import java.util.Optional;

/**
 * Verifies the access tokens auth-service issues: signature, algorithm and expiry.
 *
 * <p><b>It must accept exactly the tokens auth-service's own verifier accepts.</b> So it
 * mirrors auth-service's JwtService deliberately: the same app.jwt.secret, the same key
 * derivation ({@code Keys.hmacShaKeyFor} over the secret's UTF-8 bytes), HS256, no
 * clock-skew allowance, and the same jjwt version, which the root pom enforces.
 *
 * <p><b>Non-blocking.</b> Verifying is an HMAC over a few hundred bytes, pure CPU with no
 * I/O, so it runs inline on the Netty event loop without stalling it. The parser is
 * immutable and thread-safe, and is built once.
 */
@Service
public class AccessTokenVerifier {

    /** auth-service signs with Jwts.SIG.HS256, pinned. */
    private static final String EXPECTED_ALGORITHM = "HS256";

    /** The identity a valid token carries, as the gateway forwards it. */
    public record VerifiedUser(String userId, String role) {
    }

    private final JwtParser parser;

    public AccessTokenVerifier(@Value("${app.jwt.secret}") String secret, Clock clock) {
        this.parser = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                // Expiry judged by the injected Clock, read at each verification - never
                // jjwt's system-clock default (CLAUDE.md Timekeeping). java.util.Date
                // appears only here, at jjwt's boundary.
                .clock(() -> Date.from(clock.instant()))
                .build();
    }

    /**
     * @return the user the token identifies, or empty if it is malformed, unsigned,
     *         signed with the wrong key or algorithm, expired, or missing the claims the
     *         downstream services need. Every one of those is a 401 to the caller, so
     *         they are deliberately not told apart.
     */
    public Optional<VerifiedUser> verify(String token) {
        Jws<Claims> jws;
        try {
            // parseSignedClaims refuses unsigned ("alg": "none") tokens outright.
            jws = parser.parseSignedClaims(token);
        } catch (JwtException | IllegalArgumentException ex) {
            return Optional.empty();
        }

        // Pinned on verify as it is on sign. jjwt otherwise accepts any HMAC algorithm the
        // key is long enough for, so the accepted set would depend on the secret's length.
        if (!EXPECTED_ALGORITHM.equals(jws.getHeader().getAlgorithm())) {
            return Optional.empty();
        }

        Claims claims = jws.getPayload();
        String userId = claims.getSubject();
        String role = claims.get("role", String.class);

        // booking-service parses X-User-Id as a Long, and event-service compares
        // X-User-Role to ADMIN. A token without both, in that form, is not one auth-service
        // issued, and is refused here rather than forwarded as a header nobody can use.
        if (userId == null || !userId.chars().allMatch(Character::isDigit) || userId.isEmpty()
                || role == null || role.isBlank()) {
            return Optional.empty();
        }

        return Optional.of(new VerifiedUser(userId, role));
    }
}
