package com.bookmyseat.booking.entity;

/**
 * Booking lifecycle states.
 *
 * <p>The flow is two-step: POST /api/bookings/hold writes a booking as
 * {@link #PENDING} with an expiry and takes the Redis seat holds; POST
 * /api/bookings/{id}/confirm moves it to {@link #CONFIRMED}. Nothing writes
 * CONFIRMED directly any more. A PENDING booking ends without a sale in one of two
 * ways: DELETE /api/bookings/{id} makes it {@link #CANCELLED}, and
 * ExpiredBookingSweeper makes it {@link #EXPIRED}.
 *
 * <p>Stored as the enum NAME in a VARCHAR(16) column, so adding PENDING needed no
 * migration - but it does mean a constant can never be renamed without one.
 */
public enum BookingStatus {

    /**
     * Seats are held in Redis and the booking is waiting to be confirmed. The
     * booking's expires_at matches the hold TTL.
     *
     * <p>A PENDING booking whose hold has lapsed can stay PENDING in the database for
     * up to one sweep interval (60s) before ExpiredBookingSweeper marks it EXPIRED.
     * The Redis key is already gone by then, so the seat is genuinely free. The stale
     * row is untidy, not incorrect, and confirm refuses it regardless.
     */
    PENDING,

    CONFIRMED,

    /** The owner cancelled while PENDING. Its holds were released immediately. */
    CANCELLED,

    /** The hold lapsed unconfirmed. Recorded by the sweeper after Redis already freed the seat. */
    EXPIRED
}
