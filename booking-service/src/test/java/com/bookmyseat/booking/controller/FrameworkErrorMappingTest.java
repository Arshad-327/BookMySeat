package com.bookmyseat.booking.controller;

import com.bookmyseat.booking.config.ClockConfig;
import com.bookmyseat.booking.exception.GlobalExceptionHandler;
import com.bookmyseat.booking.service.BookingService;
import com.bookmyseat.booking.service.IdempotentBookingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The framework-level rejections Spring maps itself, which this service's catch-all was
 * turning into 500s.
 *
 * <h2>Why this class has the same name in three services</h2>
 * auth-service and event-service have a file of this name with the same cases, because the
 * three services had the same hole and now answer identically. A status that drifts in one of
 * them fails there and not in the other two, which is the point of not writing three
 * differently-shaped tests for one contract.
 *
 * <p>This service already mapped a non-numeric path id; it was 405 and 415 that fell through
 * here.
 *
 * <p>The 405 case uses PUT, not GET. {@code GET /api/bookings/hold} matches
 * {@code GET /api/bookings/{id}} with id="hold" and fails on the missing X-User-Id header
 * instead - a 400 about something else entirely, which is how the first version of this probe
 * measured the wrong thing.
 */
@WebMvcTest(BookingController.class)
@Import({ClockConfig.class, GlobalExceptionHandler.class})
class FrameworkErrorMappingTest {

    private static final String KEY = "11111111-1111-1111-1111-111111111111";

    private static final String POST_PATH = "/api/bookings/hold";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private IdempotentBookingService idempotentBookingService;

    @MockBean
    private BookingService bookingService;

    @Test
    @DisplayName("the wrong method is 405 with an Allow header, not 500")
    void wrongMethodIsMethodNotAllowed() throws Exception {
        mockMvc.perform(put("/api/bookings/hold"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.error").value("Method Not Allowed"))
                .andExpect(jsonPath("$.message").value("Method PUT is not supported for this path"))
                .andExpect(jsonPath("$.path").value("/api/bookings/hold"))
                // RFC 9110 requires it, and a hand-written handler is exactly where it gets
                // dropped. Spring's own handler set it; this asserts we did not lose it.
                .andExpect(header().string(HttpHeaders.ALLOW, containsString("POST")));
    }

    @Test
    @DisplayName("an unsupported Content-Type is 415 with an Accept header, not 500")
    void unsupportedContentTypeIsUnsupportedMediaType() throws Exception {
        mockMvc.perform(post(POST_PATH)
                        .header("X-User-Id", 7L)
                        .header("Idempotency-Key", KEY)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("seats"))
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
        mockMvc.perform(post(POST_PATH)
                        .header("X-User-Id", 7L)
                        .header("Idempotency-Key", KEY)
                        .content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.message")
                        .value("Content-Type 'application/octet-stream' is not supported"));
    }

    @Test
    @DisplayName("an unparseable Content-Type is 415 too, and that is the only way to get a null one")
    void malformedContentTypeIsUnsupportedMediaType() throws Exception {
        // The other branch of the handler, and the only one a null getContentType() reaches.
        // Sent as a raw header because MediaType could not hold this value in the first place.
        mockMvc.perform(post(POST_PATH).header("Content-Type", "application/").content("{}")
                        .header("X-User-Id", 7L)
                        .header("Idempotency-Key", KEY))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.message").value("Content-Type is not a valid media type"));
    }

    @Test
    @DisplayName("a non-numeric path id is 400, as it already was")
    void nonNumericPathIdIsBadRequest() throws Exception {
        mockMvc.perform(get("/api/bookings/abc").header("X-User-Id", 7L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Parameter 'id' is not a valid value"));
    }

    @Test
    @DisplayName("a path no controller serves is 404 in the standard shape, not 500")
    void unknownPathIsNotFound() throws Exception {
        // One segment past a real route. This was a 500 "An unexpected error occurred":
        // the framework's own 404 never got to answer, because the catch-all in
        // GlobalExceptionHandler is consulted first.
        //
        // Through the gateway the same path with NO token is a 401, and that is right and
        // is not this service's doing: the gateway refuses an unauthenticated request to
        // /api/bookings/** before it is routed anywhere. This is what an authenticated
        // caller gets.
        mockMvc.perform(get("/api/bookings/1/nope").header("X-User-Id", 7L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("No endpoint for GET /api/bookings/1/nope"))
                .andExpect(jsonPath("$.path").value("/api/bookings/1/nope"));
    }
}
