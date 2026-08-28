-- auth-service initial schema.
-- Flyway owns every schema change; ddl-auto is validate (CLAUDE.md).
--
-- Timekeeping (CLAUDE.md): every timestamp is TIMESTAMP(6), never DATETIME.
-- The (6) matters - a bare TIMESTAMP is second-precision and MySQL ROUNDS a
-- fractional value on insert, which would shift an expiry by up to half a second.

CREATE TABLE users (
    id            BIGINT       AUTO_INCREMENT PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    full_name     VARCHAR(120),
    role          VARCHAR(20)  NOT NULL,
    -- Bookkeeping, not logic: the database stamps this on insert.
    created_at    TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6)
);

CREATE TABLE refresh_tokens (
    id         BIGINT       AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT       NOT NULL,
    token_hash VARCHAR(255) NOT NULL,
    -- Written by the application as Instant.now(clock).plus(ttl). Never NOW().
    expires_at TIMESTAMP(6) NOT NULL,
    revoked    BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id),
    INDEX idx_refresh_tokens_token_hash (token_hash)
);
