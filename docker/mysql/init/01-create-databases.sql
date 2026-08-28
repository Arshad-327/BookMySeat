-- BookMySeat - one database per service (CLAUDE.md: database per service,
-- no cross-schema foreign keys, no service reads another service's tables).
--
-- These databases are intentionally EMPTY. All schema is owned by Flyway
-- inside each service; nothing here creates tables.
--
-- Runs once, on first initialisation of the mysql named volume only.

CREATE DATABASE IF NOT EXISTS auth_db
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS event_db
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS booking_db
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Reserved. payment-service does not exist and is not scaffolded.
CREATE DATABASE IF NOT EXISTS payment_db
    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
