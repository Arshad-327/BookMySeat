# Concurrency design: selling a seat exactly once

Two people must never buy the same seat. Three layers defend that, each catching what
the one in front of it can miss.

## Layer 1 — Redis seat hold

Selecting seats runs one Lua script: `SET seat:hold:{show}:{seat} NX EX 600` for every
seat, all or nothing. Redis runs scripts atomically, so one request wins and the rest
get an immediate 409 without touching MySQL. Measured: 50 simultaneous requests for one
seat produced one claim; the unprotected version produced ten.

## Layer 2 — optimistic locking on the seat row

At confirmation, event-service rejects any seat that is not `AVAILABLE` and writes
through JPA `@Version`: `UPDATE … WHERE id = ? AND version = ?`. A writer holding a
stale version matches zero rows and is refused.

## Layer 3 — database constraints

`booking_seats.sold_show_seat_id` is `UNIQUE` and filled only on confirmation, in the
same transaction as the status change. MySQL has no partial indexes, so this column
stands in for `UNIQUE(show_seat_id) WHERE status = 'CONFIRMED'`: NULLs never collide,
so many pending rows coexist but only one confirmed row per seat can.
`bookings.idempotency_key` is `UNIQUE` too.

The literal `UNIQUE(show_seat_id)` was rejected. Rows are written at hold time and never
deleted, so the first expired hold would burn that seat permanently. Reclaiming stale
rows inside the hold request would make burst losers queue on a database lock instead
of being turned away by Redis — defeating layer 1.

## Why layer 1 is an optimisation and layer 3 is the guarantee

A hold is only as reliable as Redis: a key can expire mid-checkout or vanish in a
failover, and a code path that never asks Redis is unprotected. Layer 1 makes
contention cheap. A unique index is enforced by the database on every write, from
every code path.

## Why a ten-minute hold cannot be `SELECT … FOR UPDATE`

A row lock lives only as long as its transaction. Holding one for ten minutes pins a
transaction and a pooled connection while someone types card details; ten shoppers
would exhaust a ten-connection pool. The lock stalls other writers, dies if the
connection drops, cannot span the separate hold and confirm requests, and has no expiry
of its own. A Redis key with a TTL is built for exactly this.
