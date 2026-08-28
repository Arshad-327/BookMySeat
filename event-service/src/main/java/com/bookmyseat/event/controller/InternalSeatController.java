package com.bookmyseat.event.controller;

import com.bookmyseat.event.dto.request.BookSeatsRequest;
import com.bookmyseat.event.dto.response.SeatsBookedResponse;
import com.bookmyseat.event.service.InternalSeatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service endpoints. Called by booking-service, never by a browser.
 *
 * <h2>TODO - api-gateway (P4.1): DO NOT ROUTE /api/internal/** PUBLICLY</h2>
 * When api-gateway is built it must expose /api/events/**, /api/shows/** and
 * /api/admin/** only. /api/internal/** must have no gateway route at all - it is
 * reachable on the Docker network by service name (http://event-service:8082) and
 * must stay that way. A gateway route here would let any client on the internet
 * flip seats to BOOKED, because this endpoint performs no authorisation and no
 * validation of who is calling.
 *
 * <p>It is also not covered by AdminRoleInterceptor, which is scoped to
 * /api/admin/**. That is deliberate - booking-service is a service, not an admin -
 * but it does mean this endpoint is entirely unauthenticated today.
 *
 * <h2>DELIBERATELY UNSAFE</h2>
 * The booking flow this serves has a known race condition, kept in place on
 * purpose so it can be measured under load before being fixed. See
 * {@link com.bookmyseat.event.repository.ShowSeatRepository#markBooked} for
 * exactly which protections are missing and why.
 */
@RestController
@RequestMapping("/api/internal")
@RequiredArgsConstructor
@Tag(name = "Internal", description = "Service-to-service only. Must not be routed through the gateway.")
public class InternalSeatController {

    private final InternalSeatService internalSeatService;

    @Operation(
            summary = "Mark show seats BOOKED (internal, unguarded)",
            description = """
                    Blind UPDATE of `show_seats.status` to `BOOKED` for the given ids
                    within the given show.

                    **This performs no availability check and no version check.** A seat
                    that is already BOOKED is overwritten without complaint, and the
                    response does not distinguish that case - `updated` counts rows the
                    UPDATE changed, nothing more. Two concurrent callers can therefore
                    both succeed on the same seat.

                    That is intentional for now: the race is being measured before it is
                    fixed. A later change adds the availability predicate and the
                    optimistic lock here.
                    """)
    @ApiResponses(@ApiResponse(responseCode = "200", description = "Update applied",
            content = @Content(schema = @Schema(implementation = SeatsBookedResponse.class),
                    examples = @ExampleObject(value = """
                            {
                              "showId": 301,
                              "requested": 2,
                              "updated": 2
                            }"""))))
    @PostMapping("/shows/{showId}/seats/book")
    public ResponseEntity<SeatsBookedResponse> bookSeats(
            @Parameter(description = "Show id", example = "301") @PathVariable Long showId,
            @Valid @RequestBody BookSeatsRequest request) {

        return ResponseEntity.ok(internalSeatService.markBooked(showId, request));
    }
}
