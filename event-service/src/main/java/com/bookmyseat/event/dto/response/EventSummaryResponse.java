package com.bookmyseat.event.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * An event as a card on the browse page: what it is, where, when the next show is and
 * what it costs from.
 *
 * <h2>{@code nextShowStartsAt} and {@code fromPrice} are never null</h2>
 * Not by validation - by construction. GET /api/events lists an event only if it has a
 * show starting at or after the current instant (the EXISTS filter in
 * {@code EventSpecifications}), and these two fields are aggregates over exactly those
 * shows. A client can render both unconditionally; there is no "no upcoming shows" card to
 * design. That guarantee belongs to the filter, not to this record: see the interlock
 * note there before loosening it.
 *
 * <h2>What {@code fromPrice} is, and what it is not</h2>
 * It is the lowest {@code shows.base_price} among the event's upcoming shows. It is NOT
 * the price of the cheapest seat still on sale, and it does not look at show_seats at all.
 *
 * <p>Today those are the same number, for a reason that is true and not enforced:
 * {@code AdminMapper.toShowSeat} writes every seat of a show at that show's base price, and
 * nothing updates a seat's price afterwards. The day per-seat pricing arrives - a premium
 * row, a discounted restricted view - this field keeps meaning "lowest base price" and
 * stops meaning "cheapest seat". It would not become wrong, but a card saying "from 450"
 * above a seat map whose cheapest seat is 300 would be. Whoever adds per-seat pricing
 * inherits the decision of whether to re-derive this from show_seats, at the cost of an
 * aggregate over every seat on the page.
 *
 * <h2>The list cannot be sorted by {@code nextShowStartsAt}</h2>
 * Sorting applies to Event properties in the page query; this field comes from a separate
 * statement that runs after it. "Soonest first" is therefore not available, and
 * {@code ?sort=nextShowStartsAt} is a 400.
 */
@Schema(description = "An event as it appears in a list. No shows: use GET /api/events/{id} for those.")
public record EventSummaryResponse(

        @Schema(example = "42")
        Long id,

        @Schema(example = "Coldplay - Music of the Spheres")
        String title,

        @Schema(example = "CONCERT")
        String category,

        @Schema(example = "https://cdn.bookmyseat.local/posters/42.jpg")
        String posterUrl,

        VenueResponse venue,

        @Schema(description = "Start of the event's earliest upcoming show, UTC with a trailing Z "
                + "(CLAUDE.md Timekeeping). Never null: an event with no upcoming show is not "
                + "listed. Not a sortable property.",
                example = "2026-09-14T18:30:00Z")
        Instant nextShowStartsAt,

        @Schema(description = "The lowest base price among the event's upcoming shows. Never null. "
                + "Derived from shows.base_price - it is not the price of the cheapest seat "
                + "still available.",
                example = "450.00")
        BigDecimal fromPrice
) {
}
