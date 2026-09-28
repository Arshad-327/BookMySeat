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
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Review finding #4, case 1: a body Jackson cannot read is 400, not 500.
 *
 * <h2>Why this was a 500 and why the shape of the bug matters more than the fix</h2>
 * Spring maps {@code HttpMessageNotReadableException} to 400 by itself, and auth-service was
 * getting 500 anyway. {@code GlobalExceptionHandler} does not extend
 * {@code ResponseEntityExceptionHandler} and had a {@code @ExceptionHandler(Exception.class)}
 * catch-all; {@code ExceptionHandlerExceptionResolver} runs before
 * {@code DefaultHandlerExceptionResolver}, so the catch-all matched first and the framework's
 * own mapping was never consulted. A catch-all in an advice silently outranks every default
 * Spring would otherwise apply, which is why this class also exists as the reminder.
 *
 * <p>booking-service and event-service both already mapped this, to 400 with the same message,
 * word for word. auth-service was the odd one out rather than the holder of a third opinion,
 * so the handler was copied rather than designed.
 *
 * <p>Web slice: no database and no container. What is under test is the resolver ordering and
 * the response shape, neither of which involves a row.
 */
@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtService.class, ClockConfig.class,
        GlobalExceptionHandler.class})
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-secret-value-of-sufficient-length-0123456789",
        "app.jwt.access-token-ttl=15m",
        "app.jwt.refresh-token-ttl=7d"
})
class MalformedRequestBodyTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @Test
    @DisplayName("syntactically broken JSON is 400 with the standard error shape, and the service is never called")
    void brokenJsonIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        // The exact body from the finding: a truncated object.
                        .content("{\"email\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Request body is missing or malformed"))
                .andExpect(jsonPath("$.path").value("/api/auth/login"))
                // The one field the catch-all's body could never be told apart by: a 500 and a
                // 400 both carry a timestamp, so this asserts the status and message instead.
                .andExpect(jsonPath("$.timestamp").exists());

        // Nothing reached the service, so nothing could have been half-done before the
        // rejection. Cheap to assert, and it is the difference between a rejected request and
        // a failed one.
        verifyNoInteractions(authService);
    }

    @Test
    @DisplayName("an absent body is the same 400, not a 500")
    void missingBodyIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request body is missing or malformed"));
    }

    @Test
    @DisplayName("a field of the wrong JSON type is the same 400 - it never reaches bean validation")
    void wrongJsonTypeIsBadRequest() throws Exception {
        // An object where a string belongs. Worth its own case because it looks like a
        // validation failure and is not: Jackson fails to bind before @Valid runs, so this
        // arrives as HttpMessageNotReadableException and not as MethodArgumentNotValidException.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":{\"nested\":1},\"password\":\"correct-horse\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request body is missing or malformed"));
    }

    @Test
    @DisplayName("a readable body that fails validation still gets the field-level 400, not the generic one")
    void validationErrorsKeepTheirOwnMessage() throws Exception {
        // The regression guard on the fix: the new handler must not swallow the case that
        // already worked. These two 400s say different things on purpose - one names the
        // fields, the other cannot, because there was nothing to bind them from.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("email is required")));
    }
}
