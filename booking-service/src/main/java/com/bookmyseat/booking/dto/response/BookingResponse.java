package com.bookmyseat.booking.dto.response;

import com.bookmyseat.booking.entity.BookingStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A booking, with enough about the show and the seats to render it for a person without
 * a second request.
 *
 * <h2>WHICH FIELDS CAN BE NULL - a client needs a fallback for exactly these</h2>
 * <b>{@code eventId}, {@code eventTitle}, {@code venueName}, {@code showStartsAt}</b>, and on
 * each seat <b>{@code rowLabel} and {@code seatNumber}</b>. Two causes:
 *
 * <ul>
 *   <li><b>The booking was held before these fields existed.</b> They are copied onto the
 *       booking at hold time, and bookings held earlier were never backfilled - there was
 *       nothing left to copy from. On such a booking all six are null together.
 *       <b>"Booking #12", with seat ids instead of labels, is the EXPECTED rendering for
 *       it, not a bug.</b>
 *   <li><b>event-service did not supply one of them when the seats were held.</b> The hold
 *       succeeds anyway - these are printed, never decided with - and the missing value is
 *       stored as null. {@code eventId}, {@code eventTitle} and {@code venueName} can be
 *       null individually this way. {@code showStartsAt} cannot: a hold is refused without
 *       a start time.
 * </ul>
 *
 * <p>{@code expiresAt} is also nullable, for an older and unrelated reason: it is cleared
 * when a booking is CONFIRMED.
 *
 * <p><b>Never null:</b> {@code id}, {@code userId}, {@code showId}, {@code status},
 * {@code totalAmount}, {@code createdAt}, {@code seats}, and each seat's {@code showSeatId}
 * and {@code price}.
 *
 * <h2>Where the show fields come from, and how fresh they are</h2>
 * They are a copy made when the seats were held, not a live read of event-service.
 * {@code eventTitle}, {@code venueName} and the seat labels are snapshots on purpose: a
 * ticket names what was bought. {@code showStartsAt} is a copy that is correct only
 * because a show cannot be rescheduled - see {@code Booking#showStartsAt}.
 *
 * <p>{@code showStartsAt}, not {@code startsAt} as the seat map calls it: this record
 * already has {@code expiresAt} and {@code createdAt}, both about the booking, and a bare
 * "starts at" beside them would read as one more fact about the booking.
 */
@Schema(description = "A booking")
public record BookingResponse(

        @Schema(example = "1")
        Long id,

        @Schema(example = "7")
        Long userId,

        @Schema(example = "301")
        Long showId,

        @Schema(description = "The event the show belongs to - the id GET /api/events/{id} takes. "
                + "Null on a booking held before this field existed.",
                nullable = true, example = "42")
        Long eventId,

        @Schema(description = "The event's title as it was when the seats were held. A snapshot: "
                + "it does not follow a later rename. Null on a booking held before this "
                + "field existed - render the booking by its id.",
                nullable = true, example = "Coldplay - Music of the Spheres")
        String eventTitle,

        @Schema(description = "The venue's name as it was when the seats were held. Null on a "
                + "booking held before this field existed.",
                nullable = true, example = "Phoenix Arena")
        String venueName,

        @Schema(description = "When the show starts, UTC with a trailing Z. Copied when the "
                + "seats were held. Null on a booking held before this field existed.",
                nullable = true, example = "2026-09-14T18:30:00Z")
        Instant showStartsAt,

        @Schema(
                description = "PENDING after /hold, CONFIRMED after /confirm, CANCELLED after "
                        + "DELETE, EXPIRED once the hold lapses unconfirmed.",
                example = "PENDING")
        BookingStatus status,

        @Schema(example = "900.00")
        BigDecimal totalAmount,

        @Schema(
                description = "When the seat holds lapse, while PENDING. Null once "
                        + "CONFIRMED - a confirmed booking does not expire. Kept on "
                        + "CANCELLED and EXPIRED, as the time the hold would have lapsed.",
                nullable = true,
                example = "2026-08-28T17:14:42.113204Z")
        Instant expiresAt,

        @Schema(description = "UTC, trailing Z", example = "2026-08-24T15:31:46.036032Z")
        Instant createdAt,

        @Schema(description = "The booking's seats in venue order - row, then seat number - "
                + "whatever order they were requested in. The order is stable between requests.")
        List<BookingSeatResponse> seats
) {
}
