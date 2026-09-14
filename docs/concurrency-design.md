# Concurrency design: selling a seat exactly once

Two people must never buy the same seat. Three layers defend that, each catching what
the one in front of it can miss.

## Layer 1 — Redis seat hold

Selecting seats runs one Lua script that sets a key per seat with `SET NX EX 600`, all
or nothing. Redis runs scripts atomically, so one request wins and the rest get an
immediate 409 without touching MySQL. Measured: fifty simultaneous requests for one seat
produced one claim. The unprotected version produced ten.

## Layer 2 — optimistic locking on the seat row

At confirmation, event-service refuses any seat that is not available, and writes
through a version column. A writer holding a stale version matches zero rows and is
refused.

## Layer 3 — database constraints

`booking_seats.sold_show_seat_id` is unique, and filled only on confirmation, in the
same transaction as the status change. MySQL has no partial indexes, so this column
stands in for "unique seat, where confirmed". Nulls never collide, so many pending rows
coexist, but only one confirmed row per seat can.

A plain unique seat id was rejected. Rows are written at hold time and never deleted,
so the first expired hold would burn that seat for good.

## Why layer 1 is an optimisation and layer 3 is the guarantee

A hold is only as reliable as Redis. A key can expire mid-checkout or vanish in a
failover, and a code path that never asks Redis is unprotected. Layer 1 makes contention
cheap. A unique index is enforced by the database on every write, from every code path.

## Why a ten-minute hold cannot be `SELECT … FOR UPDATE`

A row lock lives only as long as its transaction. Holding one for ten minutes pins a
pooled connection while someone types card details, so ten shoppers exhaust a
ten-connection pool. A Redis key with a TTL is built for exactly this.

## Idempotency — a retry must not buy twice

A client that times out will retry, and the retry must not create a second booking. So
the hold request requires an Idempotency-Key, with the same two-layer shape as the seat.
Redis maps the key to its booking and answers a replay in one round trip. That is fast,
but it is a cache, and caches get evicted. Behind it, the key column is unique. With
Redis empty, a replay fails the insert, and the service returns the original booking.

Confirm needs no such guarantee, because confirm creates nothing. The booking already
exists, and a second confirm finds it no longer pending and is refused. Creation needs a
key. A state transition carries its own guard.

## Ending a hold without a sale

A hold can end unsold in three ways, and only one of them is the mechanism.

The mechanism is the TTL. After ten minutes Redis deletes the key, and the seat is free,
whether or not anything else runs.

The other two are reconciliation. Cancel frees the seats immediately, because nobody
should watch an abandoned checkout keep its seats. A sweeper runs every minute and marks
lapsed bookings expired, so the database tells the truth. Both release keys with a
compare-and-delete script, so neither can free a seat someone else has since taken. Stop
the sweeper, and no seat stays locked. You just get stale rows.

Cancel created a new race. Cancel and confirm could both read the booking as pending and
both write, leaving it cancelled while its seats were booked. So confirm, cancel and the
sweeper now lock the booking row with `SELECT … FOR UPDATE`, the very thing the seat hold
must never use.

The difference is who waits, and for how long. The seat hold is contended by many users,
across human think time. A lock there pins a connection per shopper and exhausts the
pool. The booking row is contended only by its one owner's own requests, for the length
of one machine call. A lock is not wrong in itself. A lock held across human think time
by many contenders is.
