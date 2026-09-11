package com.bookmyseat.booking.controller;

import com.bookmyseat.booking.dto.request.CreateBookingRequest;
import com.bookmyseat.booking.dto.response.BookingResponse;
import com.bookmyseat.booking.dto.response.ErrorResponse;
import com.bookmyseat.booking.dto.response.SeatConflictResponse;
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
 * Booking endpoints. Two steps: hold the seats, then confirm.
 *
 * <h2>Why there is no single "book" call any more</h2>
 * There used to be one POST /api/bookings that read availability and wrote the
 * booking in the same breath. It was measurably unsafe - 50 concurrent requests
 * for one seat produced ten bookings for that seat. Splitting the call is what
 * makes exclusive ownership a thing a client can obtain and hold: the hold is
 * taken atomically in Redis, and confirm cannot succeed unless the caller still
 * owns it.
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
@Tag(name = "Bookings", description = "Hold seats, then confirm")
public class BookingController {

    private final BookingService bookingService;

    @Operation(
            summary = "Hold seats for a show (step 1 of 2)",
            description = """
                    Creates a booking with status `PENDING` and `expiresAt` set to the
                    hold TTL, and takes an exclusive hold on every requested seat in
                    Redis.

                    **All or nothing.** If any seat is already held, none are taken,
                    no booking is created, and the response is 409 listing every
                    conflicting seat - not just the first one found.

                    A 201 here means the seats are genuinely yours until `expiresAt`.
                    That is a real guarantee, unlike the old single-call endpoint,
                    where a 201 only meant the seat looked free when it was read.

                    Call `POST /api/bookings/{id}/confirm` before the hold expires.
                    If Redis is unreachable this returns **503 and creates nothing** -
                    a seat is never booked without a hold.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Seats held; booking is PENDING",
                    content = @Content(schema = @Schema(implementation = BookingResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "id": 1,
                                      "userId": 7,
                                      "showId": 1,
                                      "status": "PENDING",
                                      "totalAmount": 900.00,
                                      "expiresAt": "2026-08-28T17:14:42.113204Z",
                                      "createdAt": "2026-08-28T17:04:42.113204Z",
                                      "seats": [
                                        { "showSeatId": 1, "price": 450.00 },
                                        { "showSeatId": 2, "price": 450.00 }
                                      ]
                                    }"""))),
            @ApiResponse(responseCode = "400", description = "Validation failed, or seats not in this show",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "No such show",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409",
                    description = "A seat is already held by another booking, or already sold",
                    content = @Content(schema = @Schema(implementation = SeatConflictResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "timestamp": "2026-08-28T17:04:42.113204Z",
                                      "status": 409,
                                      "error": "Conflict",
                                      "message": "Seats are currently held by another booking: [1, 7]",
                                      "path": "/api/bookings/hold",
                                      "conflictingSeatIds": [1, 7]
                                    }"""))),
            @ApiResponse(responseCode = "503",
                    description = "Redis or event-service unreachable. No booking was created.",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/hold")
    public ResponseEntity<BookingResponse> holdSeats(
            @Parameter(description = "Caller's user id. Injected by the gateway in future; unverified today.",
                    example = "7", required = true)
            @RequestHeader("X-User-Id") Long userId,

            @Valid @RequestBody CreateBookingRequest request) {

        BookingResponse held = bookingService.hold(userId, request);
        return ResponseEntity.created(URI.create("/api/bookings/" + held.id())).body(held);
    }

    @Operation(
            summary = "Confirm a held booking (step 2 of 2)",
            description = """
                    Re-verifies that every seat hold still belongs to this booking,
                    marks the seats `BOOKED` in event-service, and moves the booking
                    to `CONFIRMED`.

                    Fails with 409 if the booking is not `PENDING`, if it has expired,
                    if any hold has lapsed or been taken by someone else, or if a seat
                    is already sold - refused by event-service's seat write, or by the
                    database's one-confirmed-booking-per-seat constraint. Holds are
                    released after the commit; they would expire on their own anyway.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Booking confirmed",
                    content = @Content(schema = @Schema(implementation = BookingResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "id": 1,
                                      "userId": 7,
                                      "showId": 1,
                                      "status": "CONFIRMED",
                                      "totalAmount": 900.00,
                                      "expiresAt": null,
                                      "createdAt": "2026-08-28T17:04:42.113204Z",
                                      "seats": [
                                        { "showSeatId": 1, "price": 450.00 },
                                        { "showSeatId": 2, "price": 450.00 }
                                      ]
                                    }"""))),
            @ApiResponse(responseCode = "404", description = "No such booking, or not the caller's",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409",
                    description = "Not PENDING, expired, or the hold was lost",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "timestamp": "2026-08-28T17:04:42.113204Z",
                                      "status": 409,
                                      "error": "Conflict",
                                      "message": "Booking 1 no longer holds seat(s) [1]; the hold expired or was taken by another booking",
                                      "path": "/api/bookings/1/confirm"
                                    }"""))),
            @ApiResponse(responseCode = "503", description = "Redis or event-service unreachable",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{id}/confirm")
    public ResponseEntity<BookingResponse> confirmBooking(
            @Parameter(description = "Caller's user id", example = "7", required = true)
            @RequestHeader("X-User-Id") Long userId,

            @Parameter(description = "Booking id returned by /hold", example = "1")
            @PathVariable Long id) {

        return ResponseEntity.ok(bookingService.confirm(userId, id));
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
