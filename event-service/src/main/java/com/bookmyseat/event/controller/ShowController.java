package com.bookmyseat.event.controller;

import com.bookmyseat.event.dto.response.ErrorResponse;
import com.bookmyseat.event.dto.response.SeatMapResponse;
import com.bookmyseat.event.service.ShowService;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/shows")
@RequiredArgsConstructor
@Tag(name = "Shows", description = "Seat maps for a scheduled show")
public class ShowController {

    private final ShowService showService;

    @Operation(
            summary = "Get the seat map for a show, grouped by row",
            description = """
                    The highest-traffic endpoint in the system. Served by two SQL
                    statements and no more: the show with its event and venue, for the
                    header, then show_seats JOIN FETCH seats, ordered in SQL and grouped
                    into rows in memory. There is no per-seat or per-row follow-up query.

                    `eventTitle`, `venueName` and `startsAt` are the header a deep-linked
                    client needs to label the grid it is rendering. `startsAt` is also what
                    booking-service reads to refuse a hold on a show that has already
                    started - it is not only decoration. `eventId` is how that client
                    links up to the event: there is no GET /api/shows/{id}, and with the
                    event id here nothing is left for one to answer.

                    `status` is only ever AVAILABLE or BOOKED. A seat another user is
                    part-way through booking still reads AVAILABLE here, because holds
                    are Redis keys with a 10-minute TTL and are never written to the
                    database. This response is a display snapshot and confers no claim
                    on a seat - only the booking call decides who gets it.

                    Each seat's `id` is the show_seats id, which is what a booking
                    request references. It is not the venue-level seat id.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The seat map",
                    content = @Content(schema = @Schema(implementation = SeatMapResponse.class),
                            examples = @ExampleObject(name = "Two rows", value = """
                                    {
                                      "showId": 301,
                                      "eventId": 42,
                                      "eventTitle": "Coldplay - Music of the Spheres",
                                      "venueName": "DY Patil Stadium",
                                      "startsAt": "2026-09-14T18:30:00Z",
                                      "totalSeats": 4,
                                      "availableSeats": 3,
                                      "rows": [
                                        {
                                          "rowLabel": "A",
                                          "seats": [
                                            { "id": 9001, "rowLabel": "A", "seatNumber": 1, "price": 450.00, "status": "AVAILABLE" },
                                            { "id": 9002, "rowLabel": "A", "seatNumber": 2, "price": 450.00, "status": "BOOKED" }
                                          ]
                                        },
                                        {
                                          "rowLabel": "B",
                                          "seats": [
                                            { "id": 9003, "rowLabel": "B", "seatNumber": 1, "price": 400.00, "status": "AVAILABLE" },
                                            { "id": 9004, "rowLabel": "B", "seatNumber": 2, "price": 400.00, "status": "AVAILABLE" }
                                          ]
                                        }
                                      ]
                                    }"""))),
            @ApiResponse(responseCode = "404", description = "No such show, or a show with no seats",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "timestamp": "2026-08-24T15:31:46.036032Z",
                                      "status": 404,
                                      "error": "Not Found",
                                      "message": "Show 999 not found",
                                      "path": "/api/shows/999/seats"
                                    }""")))
    })
    @GetMapping("/{id}/seats")
    public ResponseEntity<SeatMapResponse> getSeatMap(
            @Parameter(description = "Show id", example = "301")
            @PathVariable Long id) {

        return ResponseEntity.ok(showService.findSeatMap(id));
    }
}
