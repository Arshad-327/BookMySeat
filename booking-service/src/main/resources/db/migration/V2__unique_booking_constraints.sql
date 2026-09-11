-- booking-service V2 (booking_db): the database guarantee.
--
-- ============================================================================
-- LAYER 3 of 3 - DATABASE CONSTRAINTS
-- ============================================================================
-- Protects against: a seat being recorded as sold to two bookings, and one
-- request creating two bookings - by ANY code path. A bug, a Redis hold that was
-- lost, a race the version check did not cover, a hand-run script: the database
-- refuses the second write no matter what the application believed.
--
-- Layer 1 (the Redis seat hold) and layer 2 (@Version on event_db.show_seats)
-- sit in front of this and should mean it never fires. This is what turns that
-- "should" into "cannot". See docs/concurrency-design.md.
--
-- V1 shipped with none of these constraints on purpose, so the load test could
-- show the double sale they prevent. Its warning block is left unedited: changing
-- an applied migration changes its checksum, and Flyway then refuses to start.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. One booking per idempotency key.
-- ---------------------------------------------------------------------------
-- The column stays nullable. A unique index treats NULLs as distinct, so bookings
-- created without a key never collide with each other; only a repeated non-null
-- key is refused.
ALTER TABLE bookings
    ADD CONSTRAINT uq_bookings_idempotency_key UNIQUE (idempotency_key);

-- ---------------------------------------------------------------------------
-- 2. One CONFIRMED booking per seat.
-- ---------------------------------------------------------------------------
ALTER TABLE booking_seats
    -- DO NOT REMOVE THIS COLUMN AS REDUNDANT. It duplicates show_seat_id on purpose.
    --
    -- It expresses a partial unique index:
    --
    --     UNIQUE (show_seat_id) WHERE status = 'CONFIRMED'
    --
    -- (status being the owning booking's), which MySQL cannot declare directly
    -- because MySQL has no partial indexes. So the condition moves into the data:
    -- this column holds show_seat_id when, and only when, the booking is CONFIRMED,
    -- and is NULL otherwise. NULL-distinctness in a unique index is what lets many
    -- PENDING rows for the same seat coexist - and expired or cancelled ones - while
    -- only one confirmed row per seat is possible.
    --
    -- Why not the literal UNIQUE (show_seat_id): booking_seats rows are written at
    -- hold time and never deleted, so the first hold that expired unconfirmed would
    -- burn that seat permanently.
    --
    -- BookingService.confirm sets it in the SAME transaction as the status flip to
    -- CONFIRMED - both through Booking.confirm(). Written in a separate step, there
    -- would be a window in which a confirmed booking is not guarded by this index.
    ADD COLUMN sold_show_seat_id BIGINT NULL AFTER show_seat_id,
    ADD CONSTRAINT uq_booking_seats_sold_show_seat UNIQUE (sold_show_seat_id),
    -- Keeps the copy honest: NULL, or exactly show_seat_id. A wrong value would
    -- otherwise escape the very uniqueness the column exists to enforce. Only a code
    -- bug can trip it, so it is deliberately not part of the layer-3 409: Spring
    -- reports MySQL error 3819 as an uncategorized SQL error, not an integrity
    -- violation (pinned by BookingConstraintsMySqlTest).
    ADD CONSTRAINT chk_booking_seats_sold_matches_seat
        CHECK (sold_show_seat_id IS NULL OR sold_show_seat_id = show_seat_id);
