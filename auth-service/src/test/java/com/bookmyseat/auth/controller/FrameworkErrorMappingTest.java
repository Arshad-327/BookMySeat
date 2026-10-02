package com.bookmyseat.auth.controller;

import com.bookmyseat.auth.config.ClockConfig;
import com.bookmyseat.auth.config.JwtAuthenticationFilter;
import com.bookmyseat.auth.config.SecurityConfig;
import com.bookmyseat.auth.exception.GlobalExceptionHandler;
import com.bookmyseat.auth.service.AuthService;
import com.bookmyseat.auth.service.JwtService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The framework-level rejections Spring maps itself, which this service's catch-all was
 * turning into 500s.
 *
 * <h2>Why this class has the same name in three services</h2>
 * booking-service and event-service have a file of this name with the same cases, because
 * the three services had the same hole and now answer identically. A status that drifts in one
 * of them fails there and not in the other two, which is the whole point of not writing three
 * differently-shaped tests for one contract.
 *
 * <p>Found by probing rather than by reading: all three services answered 500 to a 405 and a
 * 415, and auth-service alone answered 500 to a non-numeric path id. See the class javadoc on
 * {@link GlobalExceptionHandler} for why extending {@code ResponseEntityExceptionHandler} is
 * not the fix.
 */
@WebMvcTest(controllers = {AuthController.class, InternalUserController.class})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, ClockConfig.class,
        GlobalExceptionHandler.class})
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-secret-value-of-sufficient-length-0123456789",
        "app.jwt.access-token-ttl=15m",
        "app.jwt.refresh-token-ttl=7d"
})
class FrameworkErrorMappingTest {

    private static final String POST_PATH = "/api/auth/login";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @Test
    @DisplayName("the wrong method is 405 with an Allow header, not 500")
    void wrongMethodIsMethodNotAllowed() throws Exception {
        mockMvc.perform(get("/api/auth/login"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.error").value("Method Not Allowed"))
                .andExpect(jsonPath("$.message").value("Method GET is not supported for this path"))
                .andExpect(jsonPath("$.path").value("/api/auth/login"))
                // RFC 9110 requires it, and a hand-written handler is exactly where it gets
                // dropped. Spring's own handler set it; this asserts we did not lose it.
                .andExpect(header().string(HttpHeaders.ALLOW, containsString("POST")));
    }

    @Test
    @DisplayName("an unsupported Content-Type is 415 with an Accept header, not 500")
    void unsupportedContentTypeIsUnsupportedMediaType() throws Exception {
        mockMvc.perform(post(POST_PATH)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("email=x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.message")
                        .value("Content-Type 'text/plain' is not supported"))
                .andExpect(header().string(HttpHeaders.ACCEPT, containsString("application/json")));
    }

    @Test
    @DisplayName("no Content-Type at all is the same 415, reported as application/octet-stream")
    void missingContentTypeIsUnsupportedMediaType() throws Exception {
        // NOT a null content type, which was the assumption this test was first written on.
        // Spring defaults an absent Content-Type to application/octet-stream, so it arrives
        // as an unsupported type like any other. Measured, then written down.
        mockMvc.perform(post(POST_PATH).content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.message")
                        .value("Content-Type 'application/octet-stream' is not supported"));
    }

    @Test
    @DisplayName("an unparseable Content-Type is 415 too, and that is the only way to get a null one")
    void malformedContentTypeIsUnsupportedMediaType() throws Exception {
        // The other branch of the handler, and the only one a null getContentType() reaches.
        // Sent as a raw header because MediaType could not hold this value in the first place.
        mockMvc.perform(post(POST_PATH).header("Content-Type", "application/").content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.message").value("Content-Type is not a valid media type"));
    }

    @Test
    @DisplayName("a non-numeric path id is 400, word for word what the other two services answer")
    void nonNumericPathIdIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/internal/users/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Parameter 'id' is not a valid value"));
    }

    @Test
    @DisplayName("a path no controller serves is 404 in the standard shape, not 500 - once the request is past Spring Security")
    void unknownPathIsNotFound() throws Exception {
        // Under /api/internal/**, which SecurityConfig permits to everyone, so the request
        // reaches routing with no token. This was a 500 "An unexpected error occurred": the
        // framework's own 404 never got to answer, because the catch-all in
        // GlobalExceptionHandler is consulted first.
        mockMvc.perform(get("/api/internal/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("No endpoint for GET /api/internal/nope"))
                .andExpect(jsonPath("$.path").value("/api/internal/nope"));
    }

    @Test
    @DisplayName("an unknown path that requires authentication is still 401 with no token: Spring Security answers before routing")
    void unknownProtectedPathWithoutATokenStaysUnauthorized() throws Exception {
        // Deliberately NOT changed by the 404 fix, and pinned so that it is not changed by
        // accident. anyRequest().authenticated() refuses the request before the dispatcher
        // looks for a handler, so whether the path exists is never asked. That is the right
        // order: telling an anonymous caller which paths exist is information they have not
        // earned. With a valid token the same path is the 404 above.
        mockMvc.perform(get("/api/auth/mee"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication is required"));
    }
}
