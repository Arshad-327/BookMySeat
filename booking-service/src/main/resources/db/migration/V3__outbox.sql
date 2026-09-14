-- booking-service V3 (booking_db): the transactional outbox.
--
-- A booking.confirmed event is written here IN THE SAME TRANSACTION as the booking's
-- status change to CONFIRMED, and published to Kafka afterwards by OutboxPublisher. A
-- database commit and a Kafka publish cannot be made atomic with each other, so the
-- database is the only thing committed atomically; see OutboxPublisher for the pattern.
--
-- Timekeeping (CLAUDE.md): created_at is TIMESTAMP(6), and it is written by the
-- application from the injected Clock rather than defaulted by the database, because
-- it is not bookkeeping here - it decides the order events are published in.
--
-- No foreign key to bookings, deliberately. An outbox row is a message waiting to leave,
-- not part of the booking aggregate, and the publisher must be able to read and mark it
-- without touching or locking the booking.

CREATE TABLE outbox (
    id           BIGINT        AUTO_INCREMENT PRIMARY KEY,
    -- The booking id, as text: the Kafka message key, so every event for one booking
    -- lands on the same partition in order.
    aggregate_id VARCHAR(64)   NOT NULL,
    event_type   VARCHAR(64)   NOT NULL,
    -- The event, serialised as JSON. Carries ids only - see BookingConfirmedEvent for why
    -- no email or other personal data is ever written here.
    payload      TEXT          NOT NULL,
    published    BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMP(6)  NOT NULL,
    -- The publisher's query: WHERE published = FALSE ORDER BY created_at, id. InnoDB appends
    -- the primary key to every secondary index, so (published, created_at) serves the id
    -- tie-break too.
    INDEX idx_outbox_published_created (published, created_at)
);
