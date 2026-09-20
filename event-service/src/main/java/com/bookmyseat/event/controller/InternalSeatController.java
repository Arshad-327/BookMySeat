package com.bookmyseat.event.controller;

import com.bookmyseat.event.dto.request.BookSeatsRequest;
import com.bookmyseat.event.dto.request.ReleaseSeatsRequest;
import com.bookmyseat.event.dto.response.ErrorResponse;
import com.bookmyseat.event.dto.response.SeatsBookedResponse;
import com.bookmyseat.event.dto.response.SeatsReleasedResponse;
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
 * <h2>NOT ROUTED THROUGH THE GATEWAY, AND IT MUST STAY THAT WAY</h2>
 * api-gateway exposes /api/auth/**, /api/events/**, /api/shows/**, /api/admin/** and
 * /api/bookings/** only. /api/internal/** has no gateway route at all, so a request for
 * it is answered 404 by the gateway itself and never reaches this service; it is
 * reachable on the Docker network by service name (http://event-service:8082) and
 * nowhere else. A route here would let any client on the internet flip seats to BOOKED -
 * or flip somebody's booked seats back to AVAILABLE - because these endpoints perform no
 * authorisation and no validation of who is calling. The release endpoint checks that the
 * seats belong to the booking in the body, which is an ownership check on the SEATS and not
 * on the caller: anyone who can name a booking id can release that booking's seats.
 *
 * <p>Enforced in three places, so adding a route cannot pass unnoticed: the gateway's
 * application.yml documents the omission, RoutingTableTest asserts the 404, and
 * scripts/e2e-smoke.sh step 13 checks it end to end against a running gateway.
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
                    in this show (**404**), if any seat is `BOOKED` to a different booking
                    or to no recorded booking (**409**), or if a row changed after it was
                    read (**409**, optimistic lock). A repeated id counts once.

                    `bookingId` is required and is recorded on every seat marked, so a
                    sold seat names the booking it was sold to. A missing one is a
                    **400** - never a seat booked with no owner.

                    **Idempotent.** A seat already `BOOKED` to the booking in the request
                    is accepted, not refused: the call is repeatable by the caller that
                    made it, which is what lets a confirm whose first attempt timed out be
                    retried. The repeat changes no row and moves no `version`, and still
                    reports every requested seat in `updated`.
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
            @ApiResponse(responseCode = "400", description = "showSeatIds or bookingId is missing",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "An id is unknown or belongs to another show",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409",
                    description = "A seat is BOOKED to another booking, or lost the optimistic lock",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/shows/{showId}/seats/book")
    public ResponseEntity<SeatsBookedResponse> bookSeats(
            @Parameter(description = "Show id", example = "301") @PathVariable Long showId,
            @Valid @RequestBody BookSeatsRequest request) {

        return ResponseEntity.ok(internalSeatService.markBooked(showId, request));
    }

    @Operation(
            summary = "Release show seats back to AVAILABLE (internal)",
            description = """
                    Puts seats back: `BOOKED` to `AVAILABLE` and the recorded owner back to
                    null, for the seats the given booking actually owns. Written through
                    managed entities, so each row's optimistic-lock `version` is checked and
                    incremented exactly as the booking call does.

                    **Only this booking's seats.** A seat owned by a different booking, a
                    seat `BOOKED` with no owner recorded, a seat already `AVAILABLE` and an
                    id that is not in this show are all skipped silently and left out of
                    `released`. The owner match is what makes a stale or repeated call a
                    no-op rather than a way to free a seat somebody else has since bought.

                    **`released: 0` is success, and is the normal answer.** This is a
                    compensation path called for every expiring or cancelled booking, and
                    almost none of those ever marked a seat in the first place. Nothing here
                    returns 404 or 409; only a malformed body is an error (**400**).
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Call completed. `released` may be 0, which is not an error",
                    content = @Content(schema = @Schema(implementation = SeatsReleasedResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "showId": 301,
                                      "requested": 2,
                                      "released": 2
                                    }"""))),
            @ApiResponse(responseCode = "400", description = "showSeatIds or bookingId is missing",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/shows/{showId}/seats/release")
    public ResponseEntity<SeatsReleasedResponse> releaseSeats(
            @Parameter(description = "Show id", example = "301") @PathVariable Long showId,
            @Valid @RequestBody ReleaseSeatsRequest request) {

        return ResponseEntity.ok(internalSeatService.release(showId, request));
    }
}
