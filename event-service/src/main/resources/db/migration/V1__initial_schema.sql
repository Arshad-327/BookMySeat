-- event-service initial schema (event_db).
-- Flyway owns every schema change; ddl-auto is validate (CLAUDE.md).
--
-- Timekeeping (CLAUDE.md): every timestamp is TIMESTAMP(6), never DATETIME.
-- The (6) matters - a bare TIMESTAMP is second-precision and MySQL ROUNDS a
-- fractional value on insert. TIMESTAMP also carries zone semantics, so with
-- connectionTimeZone=UTC the value round-trips against UTC rather than against
-- whatever zone the host JVM happens to be in; DATETIME is zone-less wall clock
-- and would reintroduce exactly that skew.
--
-- No cross-schema foreign keys: every FK below stays inside event_db.

CREATE TABLE venues (
    id      BIGINT       AUTO_INCREMENT PRIMARY KEY,
    name    VARCHAR(160) NOT NULL,
    city    VARCHAR(80)  NOT NULL,
    address VARCHAR(255),
    INDEX idx_venues_city (city)
);

CREATE TABLE events (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    title       VARCHAR(200) NOT NULL,
    description TEXT,
    category    VARCHAR(50)  NOT NULL,
    poster_url  VARCHAR(500),
    venue_id    BIGINT       NOT NULL,
    CONSTRAINT fk_events_venue FOREIGN KEY (venue_id) REFERENCES venues (id),
    INDEX idx_events_category (category)
);

CREATE TABLE shows (
    id         BIGINT        AUTO_INCREMENT PRIMARY KEY,
    event_id   BIGINT        NOT NULL,
    -- Written by the application as an Instant. Never NOW(): any "has this
    -- started / expired" comparison takes an explicit :now parameter sourced
    -- from the injected Clock (CLAUDE.md Timekeeping).
    starts_at  TIMESTAMP(6)  NOT NULL,
    base_price DECIMAL(10,2) NOT NULL,
    CONSTRAINT fk_shows_event FOREIGN KEY (event_id) REFERENCES events (id),
    INDEX idx_shows_starts_at (starts_at)
);

CREATE TABLE seats (
    id          BIGINT      AUTO_INCREMENT PRIMARY KEY,
    venue_id    BIGINT      NOT NULL,
    row_label   VARCHAR(4)  NOT NULL,
    seat_number INT         NOT NULL,
    seat_type   VARCHAR(20) NOT NULL,
    CONSTRAINT fk_seats_venue FOREIGN KEY (venue_id) REFERENCES venues (id),
    -- A venue cannot have two seats at the same row/number.
    CONSTRAINT uq_seats_venue_row_number UNIQUE (venue_id, row_label, seat_number)
);

CREATE TABLE show_seats (
    id      BIGINT        AUTO_INCREMENT PRIMARY KEY,
    show_id BIGINT        NOT NULL,
    seat_id BIGINT        NOT NULL,
    price   DECIMAL(10,2) NOT NULL,

    -- ------------------------------------------------------------------
    -- status has EXACTLY TWO values: 'AVAILABLE' and 'BOOKED'.
    --
    -- There is deliberately NO 'HELD' status in the database. A seat hold is
    -- a Redis key with a 10-minute TTL and nothing else. This is the whole
    -- design, not an omission:
    --
    --   * A hold is temporary state that must expire on its own. Redis expires
    --     it for free; a 'HELD' row would need a sweeper job, and every window
    --     between expiry and the sweeper running is a seat that looks taken
    --     but is not.
    --   * A crashed or abandoned checkout leaves no residue. There is no such
    --     thing as a stuck 'HELD' row to reconcile, because the row was never
    --     written.
    --   * It keeps the durable truth small: this table answers "is this seat
    --     sold?", which is permanent. "Is someone currently trying to buy it?"
    --     is ephemeral and lives only in Redis.
    --
    -- The transition AVAILABLE -> BOOKED happens once, at payment confirmation,
    -- guarded by the version column below (JPA @Version, optimistic locking).
    -- Two concurrent confirmations read the same version; the second UPDATE
    -- matches zero rows and its transaction fails rather than overwriting.
    -- ------------------------------------------------------------------
    status  VARCHAR(16)   NOT NULL DEFAULT 'AVAILABLE',

    -- Optimistic lock counter. Mapped to @Version on ShowSeat.
    version BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT fk_show_seats_show FOREIGN KEY (show_id) REFERENCES shows (id),
    CONSTRAINT fk_show_seats_seat FOREIGN KEY (seat_id) REFERENCES seats (id),
    -- One row per seat per show. This is the last line of defence against a
    -- double sale: even a logic bug cannot create a second row for the pair.
    -- No separate index on show_id: it is the leftmost column of the unique
    -- constraint above, so MySQL already serves WHERE show_id = ? from that
    -- index. A second one would cost writes and disk for nothing.
    CONSTRAINT uq_show_seats_show_seat UNIQUE (show_id, seat_id)
);
