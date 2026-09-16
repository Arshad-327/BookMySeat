package com.bookmyseat.auth.controller;

import com.bookmyseat.auth.config.ClockConfig;
import com.bookmyseat.auth.config.JwtAuthenticationFilter;
import com.bookmyseat.auth.config.SecurityConfig;
import com.bookmyseat.auth.dto.response.InternalUserResponse;
import com.bookmyseat.auth.exception.GlobalExceptionHandler;
import com.bookmyseat.auth.exception.UserNotFoundException;
import com.bookmyseat.auth.service.AuthService;
import com.bookmyseat.auth.service.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/internal/users/{id} - the lookup notification-service cannot do without.
 *
 * <h2>What these tests are actually defending</h2>
 * Two properties that are easy to break by a well-meaning refactor:
 *
 * <ul>
 *   <li><b>Minimal exposure.</b> The response carries the email, the name and the id, and
 *       <i>nothing else</i>. The obvious "simplification" here is to return the existing
 *       {@code UserResponse}, which also carries the role and createdAt. These tests assert
 *       the absence of those fields, so that change fails rather than quietly widening what
 *       an unauthenticated endpoint hands out.</li>
 *   <li><b>404, and not 401, for an unknown id.</b> auth-service's other "user is gone" path
 *       ({@code AuthService#getCurrentUser}) answers 401, which is right for a caller holding
 *       a token for a deleted user. It is wrong here. notification-service classifies this
 *       endpoint's failures - 404 means a deleted user and the email is permanently
 *       undeliverable, 401/403 means notification-service itself is misconfigured. If this
 *       endpoint answered 401 for a missing user, a deleted user would be logged, and chased,
 *       as a configuration fault.</li>
 * </ul>
 *
 * <p>Web slice only. No database, no Testcontainers.
 */
@WebMvcTest(InternalUserController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, ClockConfig.class,
        GlobalExceptionHandler.class})
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-secret-value-of-sufficient-length-0123456789",
        "app.jwt.access-token-ttl=15m",
        "app.jwt.refresh-token-ttl=7d"
})
class InternalUserEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @Test
    @DisplayName("returns the email and name for a known id, with NO token - it is service-to-service")
    void returnsTheEmailAndNameWithNoToken() throws Exception {
        when(authService.findInternal(42L))
                .thenReturn(new InternalUserResponse(42L, "arshad@example.com", "Arshad"));

        mockMvc.perform(get("/api/internal/users/{id}", 42L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.email").value("arshad@example.com"))
                .andExpect(jsonPath("$.fullName").value("Arshad"));
    }

    @Test
    @DisplayName("the response has EXACTLY three fields: no role, no createdAt, no passwordHash")
    void exposesNothingBeyondTheThreeFields() throws Exception {
        when(authService.findInternal(42L))
                .thenReturn(new InternalUserResponse(42L, "arshad@example.com", "Arshad"));

        MvcResult result = mockMvc.perform(get("/api/internal/users/{id}", 42L))
                .andExpect(status().isOk())
                // Named explicitly, so the failure message says which field leaked.
                .andExpect(jsonPath("$.role").doesNotExist())
                .andExpect(jsonPath("$.createdAt").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andReturn();

        // And the general form, so a field nobody thought to name above is caught too.
        assertThat(result.getResponse().getContentAsString())
                .as("the internal user projection must not grow new fields without this test being updated")
                .isEqualTo("{\"id\":42,\"email\":\"arshad@example.com\",\"fullName\":\"Arshad\"}");
    }

    @Test
    @DisplayName("an unknown id is 404 - NOT the 401 that /api/auth/me gives for the same condition")
    void unknownIdIsNotFound() throws Exception {
        when(authService.findInternal(999L)).thenThrow(new UserNotFoundException(999L));

        mockMvc.perform(get("/api/internal/users/{id}", 999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.path").value("/api/internal/users/999"))
                .andExpect(jsonPath("$.timestamp").exists());
    }
}
