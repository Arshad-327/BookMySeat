package com.bookmyseat.event.controller;

import com.bookmyseat.event.dto.request.BookSeatsRequest;
import com.bookmyseat.event.dto.response.ErrorResponse;
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
 * <h2>TODO - api-gateway (P4.1): DO NOT ROUTE /api/internal/** OR /actuator/** PUBLICLY</h2>
 * When api-gateway is built it must expose /api/events/**, /api/shows/** and
 * /api/admin/** only. /api/internal/** must have no gateway route at all - it is
 * reachable on the Docker network by service name (http://event-service:8082) and
 * must stay that way. A gateway route here would let any client on the internet
 * flip seats to BOOKED, because this endpoint performs no authorisation and no
 * validation of who is calling.
 *
 * <p><b>The same do-not-route list includes /actuator/** on every service.</b>
 * auth-service, event-service and booking-service all set
 * {@code management.endpoint.health.show-details: always}, so /actuator/health reports
 * the state of each service's database, Redis where it has one, and disk to anyone who
 * can reach it. That was left on
 * deliberately, for local diagnosis, and it is only acceptable while it is reachable on
 * the internal network alone. Health details belong there, not behind the public
 * gateway.
 *
 * <p>It is also not covered by AdminRoleInterceptor, which is scoped to
 * /api/admin/**. That is deliberate - booking-service is a service, not an admin -
 * but it does mean this endpoint is entirely unauthenticated today.
 *
 * <h2>Concurrency</h2>
 * The write behind this endpoint is layer 2 of the booking design: it goes through
 * managed ShowSeat entities, so each row's {@code @Version} is checked and
 * incremented and a stale write is rejected. It is also strict - every requested
 * seat is booked, or none is. See
 * {@link com.bookmyseat.event.service.InternalSeatService#markBooked}.
 */
@RestController
@RequestMapping("/api/internal")
@RequiredArgsConstructor
@Tag(name = "Internal", description = "Service-to-service only. Must not be routed through the gateway.")
public class InternalSeatController {

    private final InternalSeatService internalSeatService;

    @Operation(
            summary = "Mark show seats BOOKED (internal)",
            description = """
                    Sets `show_seats.status` to `BOOKED` for the given ids within the
                    given show, writing through managed entities so each row's
                    optimistic-lock `version` is checked and incremented.

                    All or nothing. The call fails and changes nothing if any id is not
                    in this show (**404**), if any seat is already `BOOKED` (**409**), or
                    if a row changed after it was read (**409**, optimistic lock).
                    A repeated id counts once.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Every requested seat is now BOOKED",
                    content = @Content(schema = @Schema(implementation = SeatsBookedResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "showId": 301,
                                      "requested": 2,
                                      "updated": 2
                                    }"""))),
            @ApiResponse(responseCode = "404", description = "An id is unknown or belongs to another show",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409",
                    description = "A seat is already BOOKED, or lost the optimistic lock",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/shows/{showId}/seats/book")
    public ResponseEntity<SeatsBookedResponse> bookSeats(
            @Parameter(description = "Show id", example = "301") @PathVariable Long showId,
            @Valid @RequestBody BookSeatsRequest request) {

        return ResponseEntity.ok(internalSeatService.markBooked(showId, request));
    }
}
