package com.bookmyseat.booking.entity;

/**
 * Booking lifecycle states.
 *
 * <p>Only CONFIRMED is ever written today: the naive flow creates a booking as
 * CONFIRMED immediately, with no pending or payment step in between. The other
 * constants exist because the column is VARCHAR(16) and the flow will grow one.
 */
public enum BookingStatus {
    CONFIRMED,
    CANCELLED,
    EXPIRED
}
