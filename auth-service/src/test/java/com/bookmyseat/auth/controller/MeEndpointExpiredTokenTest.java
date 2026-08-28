package com.bookmyseat.auth.controller;

import com.bookmyseat.auth.config.ClockConfig;
import com.bookmyseat.auth.config.JwtAuthenticationFilter;
import com.bookmyseat.auth.config.SecurityConfig;
import com.bookmyseat.auth.dto.response.UserResponse;
import com.bookmyseat.auth.entity.Role;
import com.bookmyseat.auth.entity.User;
import com.bookmyseat.auth.service.AuthService;
import com.bookmyseat.auth.service.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves that an expired access token is rejected with 401 by the real security
 * filter chain, without waiting 15 minutes: tokens are minted by a JwtService
 * built on a Clock fixed in the past.
 *
 * Web slice only. No database, no Testcontainers - the JPA layer is not loaded.
 */
@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, ClockConfig.class})
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-secret-value-of-sufficient-length-0123456789",
        "app.jwt.access-token-ttl=15m",
        "app.jwt.refresh-token-ttl=7d"
})
class MeEndpointExpiredTokenTest {

    private static final ZoneId ZONE = ZoneId.of("UTC");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Value("${app.jwt.secret}")
    private String secret;

    @MockBean
    private AuthService authService;

    private User user() {
        User user = new User();
        user.setId(42L);
        user.setEmail("expired@example.com");
        user.setFullName("Expired Token User");
        user.setRole(Role.USER);
        return user;
    }

    /**
     * A JwtService whose clock is an hour in the past, so a 15-minute token it
     * mints is already 45 minutes expired by the time the filter sees it.
     */
    private JwtService jwtServiceInThePast() {
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZONE);
        return new JwtService(secret, Duration.ofMinutes(15), Duration.ofDays(7), past);
    }

    @Test
    @DisplayName("GET /me with an EXPIRED access token is rejected with 401 and the standard error body")
    void expiredTokenIsRejected() throws Exception {
        String expiredToken = jwtServiceInThePast().generateAccessToken(user());

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.path").value("/api/auth/me"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    /**
     * Control case. Without this, a slice that rejected every request would make
     * the test above pass for the wrong reason.
     */
    @Test
    @DisplayName("GET /me with a VALID access token succeeds - proves the 401 above is caused by expiry")
    void validTokenIsAccepted() throws Exception {
        when(authService.getCurrentUser(anyLong())).thenReturn(new UserResponse(
                42L, "expired@example.com", "Expired Token User", "USER", Instant.now()));

        String validToken = jwtService.generateAccessToken(user());

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.email").value("expired@example.com"));
    }
}
