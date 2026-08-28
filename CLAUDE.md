# BookMySeat — Project Rules

This file is the project constitution. Follow it for every task in this repo, and re-read it whenever you are unsure.

## What this project is

A distributed event ticketing platform. The central engineering problem: **two users must never be able to book the same seat concurrently.** Seats are held temporarily for 10 minutes in Redis before payment. Everything else exists to serve that problem.

## Non-negotiable technical decisions

- Java 21, Spring Boot 3.3.x, Maven multi-module monorepo
- Services: `api-gateway` (Spring Cloud Gateway, reactive), `auth-service`, `event-service`, `booking-service`, `notification-service`
- `payment-service` is OPTIONAL. Do not create it unless I explicitly ask.
- MySQL 8, database per service. No cross-schema foreign keys. No service reads another service's tables.
- Redis 7 for seat holds, idempotency keys, rate-limit counters
- Kafka in KRaft mode. Exactly one topic: `booking.confirmed`, published via transactional outbox
- Flyway for all schema changes. `ddl-auto` is always `validate`, never `update` or `create`
- springdoc-openapi for Swagger
- Lombok for boilerplate only
- Frontend: React 18, Vite, JavaScript, Tailwind, Axios, TanStack Query, React Context

## Timekeeping

The development host is not UTC and the containers are. One policy, project-wide, no exceptions.

- Every instant in Java is `java.time.Instant`. Never `LocalDateTime`. This covers entities, DTOs, error-response timestamps and test fixtures, not just expiry fields
- Every timestamp column is MySQL `TIMESTAMP(6)`. Never `DATETIME`, and never a bare `TIMESTAMP`. The `(6)` is mandatory and applies to *every* timestamp column without exception — expiry fields and bookkeeping columns like `created_at` alike. It is not decoration on two counts. First, precision: a bare `TIMESTAMP` is second-precision and MySQL *rounds* a fractional value on insert, which would move a seat hold's expiry by up to half a second. Second, fidelity: `(6)` is microsecond precision, which is what `java.time.Instant` round-trips through the JDBC driver, so an `Instant` written and read back is the same value rather than a truncated one. The columns already built prove the standard — `auth_db.users.created_at`, `auth_db.refresh_tokens.expires_at`, `event_db.shows.starts_at` are all `timestamp(6)` in the live database. A migration writing anything else is wrong, not a variation
- Every JDBC URL pins `connectionTimeZone=UTC&preserveInstants=true`
- The MySQL container runs with `--default-time-zone=+00:00`. `+00:00`, not `UTC`: named zones need the timezone tables loaded and the `mysql:8` image does not load them
- No query uses SQL `NOW()` or `CURRENT_TIMESTAMP` for comparison LOGIC. Any "is this expired" query takes an explicit `:now` parameter supplied as `Instant.now(clock)` from the injected `Clock`. `DEFAULT CURRENT_TIMESTAMP(6)` on insert stays — that is bookkeeping, not logic
- Every service has a `ClockConfig` returning `Clock.systemUTC()`. Every time-based decision reads through the injected `Clock`, never through a bare `Instant.now()`, so tests can pin it
- Known limit: MySQL `TIMESTAMP` cannot hold a value past 2038-01-19T03:14:07Z. Nothing here schedules that far out. If that ever changes it is a deliberate decision, not a silent truncation

## Forbidden — do not add these, do not suggest them

Eureka or any service discovery, Resilience4j, MapStruct, Zustand, Redux, Kubernetes, Elasticsearch, GraphQL, any real payment gateway, any AI or ML feature, any additional microservice, any component library.

Services find each other by Docker Compose service name, e.g. `http://event-service:8082`

## Coding rules

- Never add a Maven or npm dependency that is not already present without asking me first and explaining why
- Never modify files outside the module I named in my request
- DTO mapping is hand-written static methods
- Every endpoint returns a DTO, never a JPA entity
- Constructor injection only. No field `@Autowired`
- Package layout per service: `config`, `controller`, `dto/request`, `dto/response`, `entity`, `repository`, `service`, `client`, `scheduler`, `exception`, `mapper`

## How I want you to work

- For any task touching more than 3 files, show me a plan and wait for approval before writing code
- After writing code, give me the exact commands to verify it works
- If my prompt contradicts this file, stop and ask. Do not guess
- NEVER invent test results, benchmark numbers, timings, or performance figures. If a number is needed, tell me which command to run and wait for me to paste the real output
- Keep changes small. Several small commits beat one large one

## Current status

Week 0. Nothing built yet.
