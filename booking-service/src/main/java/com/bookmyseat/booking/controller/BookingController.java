package com.bookmyseat.booking.controller;

import com.bookmyseat.booking.dto.request.CreateBookingRequest;
import com.bookmyseat.booking.dto.response.BookingResponse;
import com.bookmyseat.booking.dto.response.ErrorResponse;
import com.bookmyseat.booking.service.BookingService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * Booking endpoints.
 *
 * <h2>TODO - api-gateway</h2>
 * X-User-Id is expected to be injected by api-gateway from the validated
 * auth-service JWT. api-gateway does not exist yet, so the header is taken on
 * trust: any caller can claim to be any user by setting it. When the gateway is
 * built it must <b>strip</b> inbound X-User-Id before setting its own - setting
 * without stripping leaves this spoofable. Same requirement as X-User-Role on
 * event-service's admin endpoints.
 */
@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
@Tag(name = "Bookings", description = "Create and read bookings")
public class BookingController {

    private final BookingService bookingService;

    @Operation(
            summary = "Book seats for a show",
            description = """
                    **This implementation is deliberately unsafe and has a known race
                    condition.** It reads seat availability from event-service, then
                    writes the booking, holding nothing in between. Two concurrent
                    requests for the same seat can both receive 201 and the seat is
                    sold twice.

                    A 409 means one or more seats read as not AVAILABLE at the moment
                    they were checked. It is not a guarantee in the other direction: a
                    201 does not mean the seat was secured, only that it looked free
                    when it was read.

                    The user is taken from `X-User-Id`, which is currently unverified.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Booking created and CONFIRMED",
                    content = @Content(schema = @Schema(implementation = BookingResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "id": 1,
                                      "userId": 7,
                                      "showId": 1,
                                      "status": "CONFIRMED",
                                      "totalAmount": 900.00,
                                      "expiresAt": null,
                                      "createdAt": "2026-08-24T15:31:46.036032Z",
                                      "seats": [
                                        { "showSeatId": 1, "price": 450.00 },
                                        { "showSeatId": 2, "price": 450.00 }
                                      ]
                                    }"""))),
            @ApiResponse(responseCode = "400", description = "Validation failed, or seats not in this show",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No such show",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "A seat read as not AVAILABLE",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "timestamp": "2026-08-24T15:31:46.036032Z",
                                      "status": 409,
                                      "error": "Conflict",
                                      "message": "Seats not available: [2]",
                                      "path": "/api/bookings"
                                    }"""))),
            @ApiResponse(responseCode = "503", description = "event-service unreachable",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<BookingResponse> createBooking(
            @Parameter(description = "Caller's user id. Injected by the gateway in future; unverified today.",
                    example = "7", required = true)
            @RequestHeader("X-User-Id") Long userId,

            @Valid @RequestBody CreateBookingRequest request) {

        BookingResponse created = bookingService.createBooking(userId, request);
        return ResponseEntity.created(URI.create("/api/bookings/" + created.id())).body(created);
    }

    @Operation(summary = "Get one booking")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The booking",
                    content = @Content(schema = @Schema(implementation = BookingResponse.class))),
            @ApiResponse(responseCode = "404", description = "No such booking",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}")
    public ResponseEntity<BookingResponse> getBooking(
            @Parameter(description = "Booking id", example = "1") @PathVariable Long id) {
        return ResponseEntity.ok(bookingService.findById(id));
    }

    @Operation(
            summary = "List the caller's bookings",
            description = "Scoped to X-User-Id, which is unverified today - see the class note.")
    @ApiResponse(responseCode = "200", description = "Bookings, newest first")
    @GetMapping
    public ResponseEntity<List<BookingResponse>> listMyBookings(
            @Parameter(description = "Caller's user id", example = "7", required = true)
            @RequestHeader("X-User-Id") Long userId) {
        return ResponseEntity.ok(bookingService.findByUser(userId));
    }
}
