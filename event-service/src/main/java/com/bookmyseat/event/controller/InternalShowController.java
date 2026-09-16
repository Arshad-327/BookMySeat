package com.bookmyseat.event.controller;

import com.bookmyseat.event.dto.response.ErrorResponse;
import com.bookmyseat.event.dto.response.InternalShowResponse;
import com.bookmyseat.event.service.InternalShowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Service-to-service read model. Called by notification-service, never by a browser.
 *
 * <h2>An internal read model, and why that is a deliberate choice</h2>
 * This endpoint returns the show's title, venue, start time and the labels for a specific
 * set of seat ids: everything one confirmation email prints, in one response.
 *
 * <p>The alternative was to reuse what already exists. It does not fit, and the reason is
 * worth recording. The labels are available from GET /api/shows/{id}/seats, but the title
 * is not - it belongs to the EVENT, reachable only through GET /api/events/{eventId}, and
 * the consumer holds a show id. So reuse means two calls, one of which the consumer cannot
 * even address without a third. <b>Two round trips to build one message is the shape that
 * becomes an N+1 as soon as anything batches.</b>
 *
 * <p>Shaping a view for a known caller is a normal thing to do, not a compromise, provided
 * the coupling is admitted: fields exist here because notification-service prints them, and
 * the projection stays narrow for exactly that reason - no prices, no seat statuses, no
 * other shows, no venue address. InternalShowEndpointMySqlTest asserts the exact body so
 * that drift into a general-purpose show endpoint has to be deliberate.
 *
 * <h2>BEST-EFFORT by contract - the caller must survive this being down</h2>
 * Everything here is cosmetic. notification-service treats any failure of this endpoint,
 * 404 included, as a signal to send the email with raw ids rather than not send it. That is
 * the opposite of its auth-service lookup, where a missing email means no message can go at
 * all. Nothing in this service should ever be made a prerequisite for a confirmation
 * arriving.
 *
 * <h2>MUST NOT BE ROUTED THROUGH THE GATEWAY</h2>
 * Unauthenticated, like its neighbour {@link InternalSeatController} - reachable only on the
 * Docker network by service name. api-gateway has no route for /api/internal/**, and
 * RoutingTableTest pins this path specifically.
 *
 * <p>This is the clearest instance of the near miss recorded there: /api/shows/** <b>is</b> a
 * routed predicate, and this path escapes it by the segment "internal" alone.
 */
@RestController
@RequestMapping("/api/internal")
@RequiredArgsConstructor
@Tag(name = "Internal", description = "Service-to-service only. Must not be routed through the gateway.")
public class InternalShowController {

    private final InternalShowService internalShowService;

    @Operation(
            summary = "Project one show for a confirmation message (internal)",
            description = """
                    Everything a booking-confirmation email needs, in one call: the event
                    title, the venue name, the start time as a UTC instant, and printable
                    labels for exactly the seat ids given.

                    `seatIds` is optional and repeatable or comma-separated. Ids that do not
                    belong to this show are **omitted, not rejected** - this read is
                    best-effort for a cosmetic purpose, and one stale id must not cost the
                    caller the labels it can have. An unknown **show**, by contrast, is a
                    404: the show's own details are most of the answer.

                    Returns no price and no seat status. The projection is narrow on purpose;
                    see the class javadoc.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The show, projected for a message",
                    content = @Content(schema = @Schema(implementation = InternalShowResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "showId": 301,
                                      "eventTitle": "Coldplay - Music of the Spheres",
                                      "venueName": "DY Patil Stadium",
                                      "startsAt": "2026-09-14T18:30:00Z",
                                      "seats": [
                                        { "id": 9001, "label": "C2" },
                                        { "id": 9002, "label": "C3" }
                                      ]
                                    }"""))),
            @ApiResponse(responseCode = "404", description = "No such show",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "timestamp": "2026-09-16T10:12:04.118427Z",
                                      "status": 404,
                                      "error": "Not Found",
                                      "message": "Show 999 not found",
                                      "path": "/api/internal/shows/999"
                                    }""")))
    })
    @GetMapping("/shows/{id}")
    public ResponseEntity<InternalShowResponse> getShowForConfirmation(
            @Parameter(description = "Show id", example = "301")
            @PathVariable Long id,

            @Parameter(description = "show_seats ids to label. Comma-separated or repeated.",
                    example = "9001,9002")
            @RequestParam(name = "seatIds", required = false) List<Long> seatIds) {

        return ResponseEntity.ok(
                internalShowService.findForConfirmation(id, seatIds == null ? List.of() : seatIds));
    }
}
