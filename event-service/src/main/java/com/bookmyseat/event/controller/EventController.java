package com.bookmyseat.event.controller;

import com.bookmyseat.event.dto.response.ErrorResponse;
import com.bookmyseat.event.dto.response.EventDetailResponse;
import com.bookmyseat.event.dto.response.EventSummaryResponse;
import com.bookmyseat.event.dto.response.PageResponse;
import com.bookmyseat.event.service.EventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
@Tag(name = "Events", description = "Browse the event catalogue")
public class EventController {

    private final EventService eventService;

    @Operation(
            summary = "List events, filtered and paged",
            description = """
                    Every filter is optional and independent; omitting one removes it
                    from the query entirely rather than widening it. `city` and
                    `category` are exact matches, `q` is a contains-match on the event
                    title and is case-insensitive. LIKE wildcards in `q` are escaped,
                    so a literal `%` matches a percent sign and nothing else.

                    Sorting accepts any Event property, e.g. `?sort=title,asc`.
                    Page size is capped at 100.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "A page of events",
                    content = @Content(schema = @Schema(implementation = PageResponse.class),
                            examples = @ExampleObject(name = "One page", value = """
                                    {
                                      "content": [
                                        {
                                          "id": 42,
                                          "title": "Coldplay - Music of the Spheres",
                                          "category": "CONCERT",
                                          "posterUrl": "https://cdn.bookmyseat.local/posters/42.jpg",
                                          "venue": {
                                            "id": 12,
                                            "name": "Phoenix Arena",
                                            "city": "Bengaluru",
                                            "address": "42 MG Road, Bengaluru 560001"
                                          }
                                        }
                                      ],
                                      "page": 0,
                                      "size": 20,
                                      "totalElements": 1,
                                      "totalPages": 1,
                                      "last": true
                                    }"""))),
            @ApiResponse(responseCode = "400", description = "Unknown sort property",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "timestamp": "2026-08-24T15:31:46.036032Z",
                                      "status": 400,
                                      "error": "Bad Request",
                                      "message": "Unknown sort property: titel",
                                      "path": "/api/events"
                                    }""")))
    })
    @GetMapping
    public ResponseEntity<PageResponse<EventSummaryResponse>> listEvents(

            @Parameter(description = "Exact venue city", example = "Bengaluru")
            @RequestParam(required = false) String city,

            @Parameter(description = "Exact event category", example = "CONCERT")
            @RequestParam(required = false) String category,

            @Parameter(description = "Case-insensitive contains-match on title", example = "coldplay")
            @RequestParam(required = false) String q,

            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.ASC)
            Pageable pageable) {

        return ResponseEntity.ok(eventService.findEvents(city, category, q, pageable));
    }

    @Operation(
            summary = "Get one event with its venue and upcoming shows",
            description = """
                    `upcomingShows` contains only shows starting at or after the current
                    instant, earliest first; past shows are filtered out in SQL. The
                    boundary instant comes from the injected Clock, never from SQL NOW().

                    An event with no future shows returns 200 with an empty
                    `upcomingShows` array, not 404.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The event",
                    content = @Content(schema = @Schema(implementation = EventDetailResponse.class),
                            examples = @ExampleObject(name = "Event with two shows", value = """
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
                                      },
                                      "upcomingShows": [
                                        { "id": 301, "startsAt": "2026-09-14T18:30:00Z", "basePrice": 450.00 },
                                        { "id": 302, "startsAt": "2026-09-15T18:30:00Z", "basePrice": 500.00 }
                                      ]
                                    }"""))),
            @ApiResponse(responseCode = "404", description = "No such event",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "timestamp": "2026-08-24T15:31:46.036032Z",
                                      "status": 404,
                                      "error": "Not Found",
                                      "message": "Event 999 not found",
                                      "path": "/api/events/999"
                                    }""")))
    })
    @GetMapping("/{id}")
    public ResponseEntity<EventDetailResponse> getEvent(
            @Parameter(description = "Event id", example = "42")
            @PathVariable Long id) {

        return ResponseEntity.ok(eventService.findEventById(id));
    }
}
