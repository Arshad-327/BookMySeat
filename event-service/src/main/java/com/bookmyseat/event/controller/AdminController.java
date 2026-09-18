package com.bookmyseat.event.controller;

import com.bookmyseat.event.dto.request.CreateEventRequest;
import com.bookmyseat.event.dto.request.CreateShowRequest;
import com.bookmyseat.event.dto.request.CreateVenueRequest;
import com.bookmyseat.event.dto.request.GenerateSeatsRequest;
import com.bookmyseat.event.dto.response.ErrorResponse;
import com.bookmyseat.event.dto.response.EventResponse;
import com.bookmyseat.event.dto.response.SeatGenerationResponse;
import com.bookmyseat.event.dto.response.ShowCreatedResponse;
import com.bookmyseat.event.dto.response.VenueResponse;
import com.bookmyseat.event.service.AdminEventService;
import com.bookmyseat.event.service.AdminShowService;
import com.bookmyseat.event.service.AdminVenueService;
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

import java.net.URI;

/**
 * Admin write surface. Every endpoint requires the header X-User-Role: ADMIN,
 * enforced by AdminRoleInterceptor. api-gateway strips any client-supplied
 * X-User-* header, in any letter case, and sets X-User-Role from the validated
 * token's role claim, so the value is trustworthy on any request that arrived
 * through the gateway. That is why event-service must never be exposed directly
 * to clients - a direct caller could name its own role.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@Tag(name = "Admin", description = "Write endpoints. Require X-User-Role: ADMIN.")
public class AdminController {

    private final AdminVenueService adminVenueService;
    private final AdminEventService adminEventService;
    private final AdminShowService adminShowService;

    @Operation(
            summary = "Create a venue",
            description = "Creates the venue only. Its seats are generated separately, "
                    + "and a show cannot be created here until they are.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created",
                    content = @Content(schema = @Schema(implementation = VenueResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "id": 12,
                                      "name": "Phoenix Arena",
                                      "city": "Bengaluru",
                                      "address": "42 MG Road, Bengaluru 560001"
                                    }"""))),
            @ApiResponse(responseCode = "400", description = "Validation failed",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "X-User-Role header absent",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "Role is not ADMIN",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/venues")
    public ResponseEntity<VenueResponse> createVenue(@Valid @RequestBody CreateVenueRequest request) {
        VenueResponse created = adminVenueService.createVenue(request);
        return ResponseEntity.created(URI.create("/api/admin/venues/" + created.id())).body(created);
    }

    @Operation(
            summary = "Generate the physical seats for a venue",
            description = """
                    Creates `rows.size() * seatsPerRow` seats, numbered 1..seatsPerRow
                    in each row. Row labels are uppercased; duplicates within one
                    request are rejected with 400.

                    Generation happens once per venue and is not additive: a venue that
                    already has seats returns 409. Concurrent calls are caught by
                    uq_seats_venue_row_number and also return 409.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Seats created",
                    content = @Content(schema = @Schema(implementation = SeatGenerationResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "venueId": 12,
                                      "rows": ["A", "B", "C"],
                                      "seatsPerRow": 10,
                                      "seatsCreated": 30
                                    }"""))),
            @ApiResponse(responseCode = "404", description = "No such venue",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Venue already has seats",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "timestamp": "2026-08-24T15:31:46.036032Z",
                                      "status": 409,
                                      "error": "Conflict",
                                      "message": "Venue 12 already has seats; generating again would duplicate them",
                                      "path": "/api/admin/venues/12/seats/generate"
                                    }""")))
    })
    @PostMapping("/venues/{id}/seats/generate")
    public ResponseEntity<SeatGenerationResponse> generateSeats(
            @Parameter(description = "Venue id", example = "12") @PathVariable Long id,
            @Valid @RequestBody GenerateSeatsRequest request) {

        SeatGenerationResponse created = adminVenueService.generateSeats(id, request);
        return ResponseEntity.created(URI.create("/api/admin/venues/" + id + "/seats")).body(created);
    }

    @Operation(summary = "Create an event at an existing venue")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created",
                    content = @Content(schema = @Schema(implementation = EventResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "id": 42,
                                      "title": "Coldplay - Music of the Spheres",
                                      "description": "The Music of the Spheres world tour, live in India.",
                                      "category": "CONCERT",
                                      "posterUrl": "https://cdn.bookmyseat.local/posters/42.jpg",
                                      "venue": {
                                        "id": 12,
                                        "name": "Phoenix Arena",
                                        "city": "Bengaluru",
                                        "address": "42 MG Road, Bengaluru 560001"
                                      }
                                    }"""))),
            @ApiResponse(responseCode = "404", description = "No such venue",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/events")
    public ResponseEntity<EventResponse> createEvent(@Valid @RequestBody CreateEventRequest request) {
        EventResponse created = adminEventService.createEvent(request);
        return ResponseEntity.created(URI.create("/api/events/" + created.id())).body(created);
    }

    @Operation(
            summary = "Create a show and its full seat map",
            description = """
                    Creates the show and, in the same transaction, one `show_seats` row
                    for every seat in the event's venue, priced at `basePrice` and
                    status `AVAILABLE`.

                    A venue with no seats returns **409**. This is deliberate and
                    load-bearing: `GET /api/shows/{id}/seats` runs a single query with
                    no existence check and reports an empty result as 404, which is
                    only correct while a seatless show cannot be created. Generate the
                    venue's seats first.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Show and seat map created",
                    content = @Content(schema = @Schema(implementation = ShowCreatedResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "id": 301,
                                      "eventId": 42,
                                      "startsAt": "2026-09-14T18:30:00Z",
                                      "basePrice": 450.00,
                                      "seatsCreated": 60
                                    }"""))),
            @ApiResponse(responseCode = "404", description = "No such event",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "The event's venue has no seats",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "timestamp": "2026-08-24T15:31:46.036032Z",
                                      "status": 409,
                                      "error": "Conflict",
                                      "message": "Venue 12 has no seats; generate seats for the venue before creating a show there",
                                      "path": "/api/admin/shows"
                                    }""")))
    })
    @PostMapping("/shows")
    public ResponseEntity<ShowCreatedResponse> createShow(@Valid @RequestBody CreateShowRequest request) {
        ShowCreatedResponse created = adminShowService.createShow(request);
        return ResponseEntity.created(URI.create("/api/shows/" + created.id() + "/seats")).body(created);
    }
}
