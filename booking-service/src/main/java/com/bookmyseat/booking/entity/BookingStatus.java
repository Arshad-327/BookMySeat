package com.bookmyseat.booking.entity;

/**
 * Booking lifecycle states.
 *
 * <p>The flow is two-step: POST /api/bookings/hold writes a booking as
 * {@link #PENDING} with an expiry and takes the Redis seat holds; POST
 * /api/bookings/{id}/confirm moves it to {@link #CONFIRMED}. Nothing writes
 * CONFIRMED directly any more.
 *
 * <p>Stored as the enum NAME in a VARCHAR(16) column, so adding PENDING needed no
 * migration - but it does mean a constant can never be renamed without one.
 */
public enum BookingStatus {

    /**
     * Seats are held in Redis and the booking is waiting to be confirmed. The
     * booking's expires_at matches the hold TTL.
     *
     * <p>Nothing sweeps these yet: a PENDING booking whose hold has lapsed stays
     * PENDING in the database until someone tries to confirm it and is refused.
     * The Redis key is gone by then, so the seat is genuinely free - the stale row
     * is untidy, not incorrect.
     */
    PENDING,

    CONFIRMED,
    CANCELLED,
    EXPIRED
}
