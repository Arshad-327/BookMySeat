package com.bookmyseat.auth.service;

import com.bookmyseat.auth.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;

@Service
@Slf4j
public class JwtService {

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * The application.yml fallback for app.jwt.secret. Long enough to satisfy
     * HS256 so local dev runs with no setup, and named so that seeing it in a
     * deployed environment is unambiguous. Must stay byte-identical to the
     * default in application.yml or the startup warning silently stops firing.
     */
    private static final String DEV_PLACEHOLDER_SECRET = "CHANGE_ME_DEV_ONLY_NOT_FOR_ANY_REAL_DEPLOYMENT";

    private final SecretKey key;
    private final Duration accessTokenTtl;
    private final Duration refreshTokenTtl;
    private final Clock clock;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-token-ttl}") Duration accessTokenTtl,
            @Value("${app.jwt.refresh-token-ttl}") Duration refreshTokenTtl,
            Clock clock) {
        // Throws if the secret is shorter than 256 bits, which is what HS256 needs.
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenTtl = accessTokenTtl;
        this.refreshTokenTtl = refreshTokenTtl;
        this.clock = clock;

        warnIfPlaceholderSecret(secret);
    }

    /**
     * Deliberately loud. The service still starts, because blocking startup would
     * break local dev, but anyone reading the log of a deployed instance cannot
     * miss that every token it issues is signable by anyone with the source.
     */
    private void warnIfPlaceholderSecret(String secret) {
        if (DEV_PLACEHOLDER_SECRET.equals(secret)) {
            log.warn("****************************************************************");
            log.warn("*  app.jwt.secret IS STILL THE BUILT-IN DEVELOPMENT PLACEHOLDER  ");
            log.warn("*                                                                ");
            log.warn("*  Every JWT this instance issues can be forged by anyone who    ");
            log.warn("*  has read the source. This is safe for local development and   ");
            log.warn("*  unsafe anywhere else.                                         ");
            log.warn("*                                                                ");
            log.warn("*  Set the APP_JWT_SECRET environment variable to a random       ");
            log.warn("*  value of at least 32 bytes before deploying.                  ");
            log.warn("****************************************************************");
        }
    }

    /**
     * Access token. sub = user id, plus email and role claims.
     */
    public String generateAccessToken(User user) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenTtl)))
                // Pinned: signWith(key) alone auto-selects HS256/384/512 from key
                // length, so a longer secret would silently change the algorithm.
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Refresh token. Opaque high-entropy random value rather than a JWT: it is
     * only ever looked up by hash in refresh_tokens, so it carries no claims
     * and nothing can be trusted from it without a database round trip.
     */
    public String generateRefreshToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Deterministic hash, so refresh_tokens.token_hash can be queried directly.
     * BCrypt is unusable here because it salts; it stays on passwords only.
     */
    public String hashRefreshToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * @return the parsed claims, or null if the token is malformed, tampered
     * with, or expired. Callers treat null as "not authenticated".
     */
    public Claims parseAccessToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    // Expiry judged by the injected Clock, not jjwt's system-clock default
                    // (CLAUDE.md Timekeeping). java.util.Date only at jjwt's boundary.
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    public Duration getAccessTokenTtl() {
        return accessTokenTtl;
    }

    public Duration getRefreshTokenTtl() {
        return refreshTokenTtl;
    }
}
