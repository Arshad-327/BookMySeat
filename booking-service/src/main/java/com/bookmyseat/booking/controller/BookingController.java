package com.bookmyseat.booking.controller;

import com.bookmyseat.booking.dto.request.CreateBookingRequest;
import com.bookmyseat.booking.dto.response.BookingResponse;
import com.bookmyseat.booking.dto.response.ErrorResponse;
import com.bookmyseat.booking.dto.response.SeatConflictResponse;
import com.bookmyseat.booking.exception.InvalidIdempotencyKeyException;
import com.bookmyseat.booking.service.BookingService;
import com.bookmyseat.booking.service.IdempotentBookingService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

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
 * <h2>Where X-User-Id comes from, and why port 8083 must never be exposed</h2>
 * Through api-gateway, X-User-Id is injected from a validated access token. The gateway's
 * JwtAuthenticationFilter strips every client-supplied X-User-* header, in any letter
 * case, then sets X-User-Id from the token's subject. A request that arrives through the
 * gateway cannot claim to be another user.
 *
 * <p><b>A client reaching port 8083 directly bypasses all of that.</b> This service reads
 * the header's value and takes it on trust, so a direct caller can be any user by setting
 * it. That is why 8081-8083 must never be exposed outside the internal network. The load
 * tests call 8083 directly on purpose; that is the trusted internal path, not a public one.
 */
@RestController
@RequestMapping("/api/bookings")
@RequiredArgsConstructor
@Tag(name = "Bookings", description = "Hold seats, then confirm or cancel")
public class BookingController {

    /** Create and confirm go through the idempotent wrapper; plain reads do not. */
    private final IdempotentBookingService idempotentBookingService;

    private final BookingService bookingService;

    /**
     * Canonicalises an Idempotency-Key, rejecting anything that is not a UUID.
     *
     * <p>Returns {@link UUID#toString()} rather than the caller's own spelling, so a key
     * sent once in upper case and retried in lower case is recognised as the same key.
     * Without that, the two differ as strings and the retry would create a second
     * booking - the exact failure the header exists to prevent.
     */
    private static String canonicalKey(String idempotencyKey) {
        try {
            return UUID.fromString(idempotencyKey.trim()).toString();
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new InvalidIdempotencyKeyException(idempotencyKey);
        }
    }

    @Operation(
            summary = "Hold seats for a show (step 1 of 2)",
            description = """
                    Creates a booking with status `PENDING` and `expiresAt` set to the
                    hold TTL, and takes an exclusive hold on every requested seat in
                    Redis.

                    **All or nothing.** If any seat is already held, none are taken,
                    no booking is created, and the response is 409 listing every
                    conflicting seat - not just the first one found.

                    A 201 here means the seats are genuinely yours until `expiresAt`:
                    no other booking can hold them until then.

                    Call `POST /api/bookings/{id}/confirm` before the hold expires, or
                    `DELETE /api/bookings/{id}` to release the seats straight away.
                    If Redis is unreachable this returns **503 and creates nothing** -
                    a seat is never booked without a hold.

                    **Idempotency-Key is required** and must be a UUID. Retrying with
                    the same value returns the booking the first attempt created, with
                    **200** instead of 201, and creates nothing further - safe whether
                    the original response was lost in transit or never arrived. A
                    different key is a different booking attempt. Keys are remembered
                    for 24 hours in Redis and, beyond that, enforced permanently by a
                    unique index, so a replay is answered correctly even if Redis has
                    evicted the key.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Replay of a previous request with this Idempotency-Key; "
                            + "the original booking, unchanged. Nothing was created.",
                    content = @Content(schema = @Schema(implementation = BookingResponse.class))),
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

            @Parameter(description = "A UUID identifying this attempt. Retry with the SAME value to "
                    + "get the original booking back instead of creating a second one.",
                    example = "3f7c1c9e-9b1a-4f2e-8d5a-6c0f1b2a3d4e", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKey,

            @Valid @RequestBody CreateBookingRequest request) {

        IdempotentBookingService.HoldOutcome outcome =
                idempotentBookingService.hold(userId, canonicalKey(idempotencyKey), request);
        BookingResponse held = outcome.booking();

        // 200 for a replay, 201 only for a booking this request actually created. A
        // retry that returned 201 would tell the caller it had created something twice.
        if (outcome.replayed()) {
            return ResponseEntity.ok(held);
        }
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

            @Parameter(description = "Optional. A UUID identifying this attempt; retrying with the "
                    + "same value returns the confirmed booking instead of the 409 a second "
                    + "confirm would otherwise get.",
                    example = "3f7c1c9e-9b1a-4f2e-8d5a-6c0f1b2a3d4e")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,

            @Parameter(description = "Booking id returned by /hold", example = "1")
            @PathVariable Long id) {

        String key = idempotencyKey == null ? null : canonicalKey(idempotencyKey);
        return ResponseEntity.ok(idempotentBookingService.confirm(userId, id, key));
    }

    @Operation(
            summary = "Cancel a held booking",
            description = """
                    Moves a `PENDING` booking to `CANCELLED` and releases its seat holds
                    immediately, so the seats can be held by someone else straight away
                    rather than after the hold expires.

                    Only a `PENDING` booking can be cancelled. That includes one whose
                    hold has lapsed but which has not yet been marked `EXPIRED`. Anything
                    else is 409, including a second cancel.

                    The booking is not deleted. It stays readable with status `CANCELLED`,
                    which is why the response is 200 with the booking rather than 204.
                    Replaying the Idempotency-Key that created it returns this cancelled
                    booking; it does not create a new one.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Booking cancelled; holds released",
                    content = @Content(schema = @Schema(implementation = BookingResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "id": 1,
                                      "userId": 7,
                                      "showId": 1,
                                      "status": "CANCELLED",
                                      "totalAmount": 900.00,
                                      "expiresAt": "2026-08-28T17:14:42.113204Z",
                                      "createdAt": "2026-08-28T17:04:42.113204Z",
                                      "seats": [
                                        { "showSeatId": 1, "price": 450.00 },
                                        { "showSeatId": 2, "price": 450.00 }
                                      ]
                                    }"""))),
            @ApiResponse(responseCode = "404", description = "No such booking, or not the caller's",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Not PENDING: already confirmed, cancelled or expired",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @DeleteMapping("/{id}")
    public ResponseEntity<BookingResponse> cancelBooking(
            @Parameter(description = "Caller's user id", example = "7", required = true)
            @RequestHeader("X-User-Id") Long userId,

            @Parameter(description = "Booking id returned by /hold", example = "1")
            @PathVariable Long id) {

        // 200 with the booking, not 204. Nothing is deleted: this is a status change, the
        // row stays and GET /{id} still returns it, which a 204 would suggest otherwise.
        // Returning the body also matches confirm, and lets the client show the final
        // state without a second request.
        return ResponseEntity.ok(bookingService.cancel(userId, id));
    }

    @Operation(
            summary = "Get one of the caller's bookings",
            description = """
                    Returns the booking only if it belongs to the caller.

                    A booking that belongs to someone else is answered with **404**,
                    and the response is identical to the one for an id that does not
                    exist. A 403 would tell the caller that the id is real.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The booking",
                    content = @Content(schema = @Schema(implementation = BookingResponse.class))),
            @ApiResponse(responseCode = "404", description = "No such booking, or not the caller's",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{id}")
    public ResponseEntity<BookingResponse> getBooking(
            @Parameter(description = "Caller's user id", example = "7", required = true)
            @RequestHeader("X-User-Id") Long userId,

            @Parameter(description = "Booking id", example = "1") @PathVariable Long id) {
        return ResponseEntity.ok(bookingService.findById(userId, id));
    }

    @Operation(
            summary = "List the caller's bookings",
            description = """
                    Every booking belonging to `X-User-Id`, in any status, newest first.

                    `X-User-Id` is not verified yet. It will be set by api-gateway from a
                    validated JWT, and until then any caller can set it.
                    """)
    @ApiResponse(responseCode = "200", description = "Bookings, newest first")
    @GetMapping
    public ResponseEntity<List<BookingResponse>> listMyBookings(
            @Parameter(description = "Caller's user id", example = "7", required = true)
            @RequestHeader("X-User-Id") Long userId) {
        return ResponseEntity.ok(bookingService.findByUser(userId));
    }
}
