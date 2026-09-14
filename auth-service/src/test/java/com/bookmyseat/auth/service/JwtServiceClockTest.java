package com.bookmyseat.auth.service;

import com.bookmyseat.auth.entity.Role;
import com.bookmyseat.auth.entity.User;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the expiry check on the VERIFY path to the injected Clock (CLAUDE.md Timekeeping).
 *
 * <p>MeEndpointExpiredTokenTest mints a token whose exp is genuinely in the past by system
 * time, so it would pass whichever clock the verifier read. It proves expired tokens are
 * rejected, not that the rejection is decided by the injected Clock.
 *
 * <p>These tests can only pass if it is. The clock is pinned in 2020, so every token here
 * is long expired by the system clock. One read of the system clock anywhere on the verify
 * path rejects the "still valid" token, and the first test fails.
 */
class JwtServiceClockTest {

    private static final String SECRET = "test-only-secret-value-of-sufficient-length-0123456789";
    private static final Duration TTL = Duration.ofMinutes(15);
    private static final Instant MINTED_AT = Instant.parse("2020-01-01T10:00:00Z");

    private static JwtService at(Instant instant) {
        return new JwtService(SECRET, TTL, Duration.ofDays(7), Clock.fixed(instant, ZoneOffset.UTC));
    }

    private static User user() {
        User user = new User();
        user.setId(42L);
        user.setEmail("clock@example.com");
        user.setRole(Role.USER);
        return user;
    }

    @Test
    @DisplayName("a token is accepted while the INJECTED clock is before exp, even though the system clock is years past it")
    void acceptedAtPinnedTimeBeforeExpiry() {
        String token = at(MINTED_AT).generateAccessToken(user());

        Claims claims = at(MINTED_AT.plus(Duration.ofMinutes(14))).parseAccessToken(token);

        assertThat(claims).as("verified at 10:14 by the injected clock; exp is 10:15").isNotNull();
        assertThat(claims.getSubject()).isEqualTo("42");
    }

    @Test
    @DisplayName("the same token is rejected once the injected clock passes exp")
    void rejectedAtPinnedTimeAfterExpiry() {
        String token = at(MINTED_AT).generateAccessToken(user());

        assertThat(at(MINTED_AT.plus(Duration.ofMinutes(16))).parseAccessToken(token))
                .as("verified at 10:16 by the injected clock; exp is 10:15")
                .isNull();
    }
}
