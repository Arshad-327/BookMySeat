-- booking-service initial schema (booking_db).
-- Flyway owns every schema change; ddl-auto is validate (CLAUDE.md).
--
-- Timekeeping (CLAUDE.md): every timestamp is TIMESTAMP(6), never DATETIME and
-- never a bare TIMESTAMP. The (6) is microsecond precision, matching what
-- java.time.Instant round-trips through the driver; a bare TIMESTAMP is
-- second-precision and MySQL ROUNDS a fractional value on insert.
--
-- No cross-schema foreign keys: show_id and show_seat_id below reference rows in
-- event_db and are deliberately plain BIGINT columns with no FK constraint. Same
-- for user_id, which belongs to auth_db. booking-service never reads those tables;
-- it asks event-service over HTTP.
--
-- ============================================================================
-- DELIBERATELY UNSAFE - NO UNIQUE CONSTRAINTS. THIS IS NOT AN OVERSIGHT.
-- ============================================================================
-- There is no unique constraint on booking_seats.show_seat_id, and none on
-- bookings.idempotency_key. Both are missing on purpose so that a load test can
-- demonstrate the failure they would prevent:
--
--   * Without UNIQUE(show_seat_id), two concurrent bookings for the same seat
--     both insert successfully and the same physical seat is sold twice. The
--     database will not stop it, because nothing here tells it to.
--   * Without UNIQUE(idempotency_key), a retried request creates a second
--     booking instead of returning the first.
--
-- These are the last line of defence that has been deliberately removed. The
-- application logic above them is equally unguarded - see BookingService. Adding
-- the constraints is a later step, and the point is to measure the difference.
-- ============================================================================

CREATE TABLE bookings (
    id              BIGINT        AUTO_INCREMENT PRIMARY KEY,
    -- From the X-User-Id header. No FK: users live in auth_db.
    user_id         BIGINT        NOT NULL,
    -- Rows in event_db. No FK, by design.
    show_id         BIGINT        NOT NULL,
    status          VARCHAR(16)   NOT NULL,
    total_amount    DECIMAL(10,2) NOT NULL,
    -- Nullable, and NOT unique. See the warning above.
    idempotency_key VARCHAR(64)   NULL,
    -- Written by the application as an Instant, never by SQL NOW().
    expires_at      TIMESTAMP(6)  NULL,
    -- Bookkeeping, not logic: the database stamps this on insert.
    created_at      TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_bookings_user (user_id),
    INDEX idx_bookings_show (show_id)
);

CREATE TABLE booking_seats (
    id           BIGINT        AUTO_INCREMENT PRIMARY KEY,
    booking_id   BIGINT        NOT NULL,
    -- A show_seats row in event_db. No FK across schemas, and deliberately NOT
    -- unique: this is exactly where the double-booking becomes visible.
    show_seat_id BIGINT        NOT NULL,
    price        DECIMAL(10,2) NOT NULL,
    CONSTRAINT fk_booking_seats_booking FOREIGN KEY (booking_id) REFERENCES bookings (id),
    INDEX idx_booking_seats_show_seat (show_seat_id)
);
