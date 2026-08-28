package com.bookmyseat.event.entity;

/**
 * The complete set of persisted seat states. There are exactly two.
 *
 * <p>There is deliberately no {@code HELD} constant. A seat hold is a Redis key
 * with a 10-minute TTL and lives nowhere else: Redis expires it for free, an
 * abandoned checkout leaves no row to reconcile, and this table stays limited to
 * the durable question "is this seat sold?". Adding a third constant here would
 * require a matching database status and would undo that design.
 */
public enum SeatStatus {
    AVAILABLE,
    BOOKED
}
