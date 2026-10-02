-- booking-service V4 (booking_db): what a booking is FOR, copied onto the booking.
--
-- A booking row has always known its show_id and its show_seat_ids and nothing a person
-- can read: no event title, no venue, no start time, no "Row C, Seat 14". Rendering
-- "My Bookings" from that gives "Booking #12 - seat 9001, 9002".
--
-- The readable facts live in event_db, which this service may not read (CLAUDE.md: no
-- service reads another service's tables). They are copied here instead, ONCE, at hold
-- time, from the seat map BookingService.hold already fetches for prices and the start
-- time. No new call to event-service is made to fill these columns, and none is made to
-- read them back: a bookings list of 20 costs event-service nothing and works while it
-- is down.
--
-- ============================================================================
-- COLUMN SIZES ARE COPIED FROM event_db, NOT CHOSEN
-- ============================================================================
-- Each one matches the column it is a copy of, read from event-service's
-- V1__initial_schema.sql. A narrower column here would truncate, or under strict mode
-- refuse, a value event_db accepted - and the failure would be a hold that 500s for one
-- long-titled event and no other.
--
--   event_db.events.id          BIGINT        ->  bookings.event_id         BIGINT
--   event_db.events.title       VARCHAR(200)  ->  bookings.event_title      VARCHAR(200)
--   event_db.venues.name        VARCHAR(160)  ->  bookings.venue_name       VARCHAR(160)
--   event_db.shows.starts_at    TIMESTAMP(6)  ->  bookings.show_starts_at   TIMESTAMP(6)
--   event_db.seats.row_label    VARCHAR(4)    ->  booking_seats.row_label   VARCHAR(4)
--   event_db.seats.seat_number  INT           ->  booking_seats.seat_number INT
--
-- If event_db ever widens one of those, this side has to follow.
--
-- ============================================================================
-- ALL SIX ARE NULLABLE, AND THERE IS NO BACKFILL
-- ============================================================================
-- Rows written before this migration have nothing to copy from - the seat map they were
-- held against is long gone from memory - and filling them in would mean calling
-- event-service once per historical booking from a migration, which a migration cannot
-- do. They stay NULL, and BookingResponse documents that a client must expect it.
--
-- NULL also has a second, permanent meaning: a hold taken against a seat map that did
-- not carry the title, venue or event id still succeeds, and stores NULL. Those are
-- things this service prints, not things it decides with. See BookingService.hold.
--
-- No foreign key on event_id: it points into another service's schema. No index on it
-- either - nothing looks a booking up by event.

ALTER TABLE bookings
    ADD COLUMN event_id       BIGINT       NULL AFTER show_id,

    -- A SNAPSHOT, AND CORRECT AS ONE. The event's title as it was when the seats were
    -- held. A ticket should name what was bought: if the event is retitled next month,
    -- this booking still says what the customer agreed to. Do not "fix" it by syncing.
    ADD COLUMN event_title    VARCHAR(200) NULL AFTER event_id,

    -- A SNAPSHOT, AND CORRECT AS ONE, for the same reason as event_title.
    ADD COLUMN venue_name     VARCHAR(160) NULL AFTER event_title,

    -- ------------------------------------------------------------------------
    -- NOT A SNAPSHOT ANYONE WANTS. THIS ONE IS A CACHED COPY, AND IT CAN GO STALE.
    -- ------------------------------------------------------------------------
    -- The title a ticket was sold under is a historical fact. The time the show starts
    -- is not: it is where the customer has to be, and if the show moves, the old time is
    -- simply wrong. Nobody wants "the start time as it was when I booked".
    --
    -- This column is safe TODAY for exactly one reason: admin writes are create-only, so
    -- a show cannot be rescheduled and shows.starts_at never changes after insert. The
    -- day a show-update endpoint exists, every booking for that show holds a stale time
    -- here, silently - no error, no log line, just a "My Bookings" page that tells
    -- people to turn up at the wrong hour.
    --
    -- WHOEVER BUILDS SHOW-UPDATE MUST ALSO CORRECT THIS COLUMN, and there is no channel
    -- to do it with: one Kafka topic (booking.confirmed, flowing the other way), and no
    -- cross-schema reads. That is a design decision to be made then, not a detail.
    -- CLAUDE.md records the dependency next to "Admin writes are create-only".
    --
    -- TIMESTAMP(6), never a bare TIMESTAMP and never DATETIME (CLAUDE.md Timekeeping):
    -- the same type as the column it copies, so the Instant survives the copy exactly.
    ADD COLUMN show_starts_at TIMESTAMP(6) NULL AFTER venue_name;

ALTER TABLE booking_seats
    -- SNAPSHOTS, AND CORRECT AS SUCH: the seat as it was labelled when it was held. The
    -- two parts are stored separately, as event-service serves them, so a client decides
    -- how to join them ("C14", "Row C, Seat 14") instead of parsing a joined string.
    ADD COLUMN row_label   VARCHAR(4) NULL AFTER sold_show_seat_id,
    ADD COLUMN seat_number INT        NULL AFTER row_label;
