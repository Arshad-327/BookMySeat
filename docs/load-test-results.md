# Load test results

Measured results for the single-seat contention test. Every number on this page is
copied from a real run; nothing is derived, rounded or estimated.

---

## Baseline — naive implementation (no protection)

**Date:** 2026-08-28 (burst fired at `2026-08-28T17:04:42Z` / `22:34:46+05:30`)

**Environment:** Windows 11 Home Single Language 10.0.26200 · AMD Ryzen 7 7840HS (8 cores / 16 logical) · 15.3 GB RAM · Temurin JDK 21.0.12.1 · MySQL 8.4.11 in Docker (`bookmyseat-mysql`, `--default-time-zone=+00:00`, host port 13306) · Docker Engine 29.6.1 · k6 v2.2.0 from `grafana/k6@sha256:5221b620a4f874faff6e32ba597aa667c058391fe4898b1c6f6377f062c6cdec` · services run as jars on the host, not in Compose.

**Code under test:** commit `2b4d82cce377ef95909d0e6f2d80f39df9250b9d` — *"Add booking-service
with the deliberately unsafe naive booking path"*. Check that revision out to reproduce
this baseline against a known state of the naive implementation:

```bash
git checkout 2b4d82c
mvn -pl event-service,booking-service -am -DskipTests package
```

> The repository was placed under version control after this run was measured, so the
> commit above is the import of the exact working tree that produced these numbers, not a
> revision that existed at the time. The jars actually executed were built from that tree
> on 2026-08-24 (`event-service-0.0.1-SNAPSHOT.jar` 22:30, `booking-service-0.0.1-SNAPSHOT.jar`
> 22:32).

### Methodology

Reproduce in this order. Every step matters; the warm-up and the reset are not optional
preliminaries, they are part of the measurement.

**1. Infrastructure**

```bash
docker compose -f docker-compose.infra.yml up -d mysql
```

Only MySQL is required. Neither service depends on Redis or Kafka at this point.

**2. Start both services on the host**

```bash
java -jar event-service/target/event-service-0.0.1-SNAPSHOT.jar
java -jar booking-service/target/booking-service-0.0.1-SNAPSHOT.jar
```

event-service runs on the **default** profile, deliberately **not** `demo`. The reset
script seeds `event_db` from a separate throwaway JVM, and a long-lived service also
running the seeder would be a second writer against the same tables.

Wait for both:

```bash
curl -s localhost:8082/actuator/health
curl -s localhost:8083/actuator/health
```

**3. Warm-up — before the reset, and before any measurement**

A cold JVM pays for JIT compilation and connection-pool initialisation on its first
requests. If that cost lands on the first VU of the burst it widens the spread off the
barrier and makes the collision look less simultaneous than it really was. So both
services are driven through the exact code path that is about to be measured — including
the 409 branch — until they are warm. Everything this writes is destroyed by the reset in
step 4, which is why the warm-up comes first.

```bash
# event-service: the seat-map read booking-service makes on every request
for i in $(seq 1 40); do curl -s -o /dev/null http://localhost:8082/api/shows/1/seats; done
for i in $(seq 1 10); do curl -s -o /dev/null http://localhost:8082/api/events; done

# booking-service: the 201 path, one distinct seat each
for s in $(seq 41 60); do
  curl -s -o /dev/null -w '%{http_code} ' -X POST http://localhost:8083/api/bookings \
    -H 'Content-Type: application/json' -H 'X-User-Id: 900' \
    -d "{\"showId\":1,\"seatIds\":[$s]}"
done

# booking-service: the 409 path, same already-booked seat each time
for i in $(seq 1 10); do
  curl -s -o /dev/null -w '%{http_code} ' -X POST http://localhost:8083/api/bookings \
    -H 'Content-Type: application/json' -H 'X-User-Id: 901' \
    -d '{"showId":1,"seatIds":[41]}'
done

# booking-service: the read path
for i in $(seq 1 10); do curl -s -o /dev/null -H 'X-User-Id: 900' http://localhost:8083/api/bookings/1; done
```

Observed during warm-up: 20 × `201`, then 10 × `409`. Both branches were compiled before
the measured run.

**4. Reset to a known state**

```bash
./load-tests/reset-fixtures.sh
```

Truncates every application table in `booking_db` and `event_db` (leaving
`flyway_schema_history` alone), then re-seeds `event_db` by running event-service's real
`DemoDataSeeder` in a throwaway headless JVM. Because `TRUNCATE` resets `AUTO_INCREMENT`,
the ids come out identical on every run — which is what makes two runs comparable at all.

The run recorded here started from the script's verified output:

```
  SHOW_ID   1
  SEAT_ID   1        <- first AVAILABLE seat, verified above

  AVAILABLE seat ids for show 1 (60 of 60):
```

with `booking_db` confirmed empty (`bookings=0  booking_seats=0`) and the running
event-service independently agreeing that seat 1 was `AVAILABLE`.

**5. The measured run — 50 VUs, straight at booking-service on port 8083**

Target is **booking-service directly on `8083`. Not through api-gateway** — api-gateway
does not exist yet, and routing the burst through a proxy would add a hop to a
timing-sensitive measurement.

```bash
MSYS_NO_PATHCONV=1 docker run --rm -i \
  -v "$PWD/load-tests:/scripts" \
  -e BASE_URL=http://host.docker.internal:8083 \
  -e SHOW_ID=1 \
  -e SEAT_ID=1 \
  -e VUS=50 \
  grafana/k6 run /scripts/single-seat-contention.js
```

`MSYS_NO_PATHCONV=1` is required under Git Bash on Windows: without it MSYS rewrites the
container-side `/scripts/...` argument into a Windows path and k6 reports the script
"couldn't be found on local disk".

| Parameter | Value |
|---|---|
| VUs | 50, one request each |
| `SHOW_ID` | 1 |
| `SEAT_ID` | 1 (a single `show_seats` id; all 50 VUs fight over it) |
| `DISTINCT_USERS` | `true` (default) — `X-User-Id` 1..50 |
| `START_DELAY_MS` | 3000 (default) |
| `JWT` | unset — booking-service has no security filter and ignores it |

### The comparison run measures a different endpoint — read this before comparing

**The baseline above measured `POST /api/bookings`. The comparison run will measure
`POST /api/bookings/hold`, and will not call confirm.** The endpoint changed between the
two runs. Stating that plainly here so nobody reading the numbers later discovers it and
wonders what else was quietly adjusted.

The endpoint had to change: the naive flow's single call was split into hold and confirm
as part of the fix, so there is no longer any endpoint that does what `POST /api/bookings`
did. The question is whether a number from the old one can be compared with a number from
the new one, and it can — because of what is actually being counted.

**The quantity being compared is "how many users obtained exclusive ownership of one
seat", not "how did one endpoint behave".** That quantity is well defined in both systems;
only its representation moved:

- In the naive system, the claim on a seat *was* the booking. Ten users held a confirmed
  booking for seat 1, so ten users had claimed it. The correct answer was one.
- In the fixed system, the claim is the hold. However many users get 201 from `/hold` is
  how many believe they own the seat. The correct answer is still one.

Comparing ten claims against however many the fixed system yields is comparing like with
like. Comparing the *endpoints* would not be, and neither would be a comparison that
required the two runs to call the same URL — the URL is not the thing under test.

**Confirm is deliberately excluded.** Adding it would fold a second round trip,
event-service's seat write and the optimistic lock into a figure that is meant to isolate
one change: whether concurrent claims on a seat are now mutually exclusive. Those later
steps have their own failure modes and their own latency, and mixing them in would make a
difference in the number impossible to attribute. Confirm is worth measuring; it is not
worth measuring *here*.

What must stay identical between the runs: 50 VUs, one seat, the same reset procedure, the
same machine, the same barrier, and the spread checked before the status counts are read.

### Validity — the requests genuinely collided

| | |
|---|---|
| Spread off the barrier (earliest → latest request) | **2 ms** (min 0 / avg 0.3 / max 2) |
| Minimum request duration | **132.36 ms** |

Every one of the 50 requests left the barrier inside a 2 ms window, while the *fastest*
response took 132.36 ms. So the last request was already in flight long before the first
one could have finished, and all 50 overlapped inside the server. That is a real
collision, not a queue.

**A wide spread would have invalidated this run.** If requests had dribbled out over a
window comparable to the 132.36 ms it takes to serve one, they would have arrived one
after another rather than together — each seeing the effect of the last — and the status
counts would describe sequential traffic, not contention. The script exists to measure a
race, and a race that did not happen measures nothing. The correct response to a wide
spread is to raise `START_DELAY_MS` and re-run, not to report the numbers.

### k6 summary, verbatim

```
  --------------------------------------------------------------
  SINGLE-SEAT CONTENTION - RAW RESULT
  --------------------------------------------------------------
  target                 http://host.docker.internal:8083/api/bookings
  showId / seatId        1 / 1
  vus                    50

  REQUESTS
    total                50
    2xx                  10
    409                  40
    other / unlisted     0

  STATUS BREAKDOWN
    201                  10
    409                  40

  BURST TIGHTNESS (how far after the release instant each request went out)
    min / avg / max      0 / 0.3 / 2 ms
    spread               2 ms
    A wide spread means the VUs did not really collide. Raise START_DELAY_MS.

  LATENCY (http_req_duration, ms)
    min / avg / med      132.36 / 214.82 / 215.14
    p95 / p99 / max      266.68 / 267.76 / 267.9

  --------------------------------------------------------------
  These counts are HTTP responses only. They do NOT tell you whether the
  seat was sold more than once - booking-service can return 201 to several
  callers without logging anything. Run the verification query in
  load-tests/README.md against booking_db to find out.
  --------------------------------------------------------------

running (0m03.3s), 00/50 VUs, 50 complete and 0 interrupted iterations
single_seat_burst ✓ [ 100% ] 50 VUs  0m03.3s/2m0s  50/50 iters, 1 per VU
```

k6 exited `0`. It sets no thresholds by design, so that exit code carries no verdict.

### Ground truth in the database

The HTTP counts above are not the result. These queries are.

```sql
SELECT COUNT(*) AS bookings_total FROM booking_db.bookings;

SELECT COUNT(*) AS booking_seats_total FROM booking_db.booking_seats;

SELECT COUNT(DISTINCT bs.booking_id) AS distinct_bookings_claiming_seat_1
  FROM booking_db.booking_seats bs
 WHERE bs.show_seat_id = 1;

SELECT ss.id AS show_seat_id, ss.show_id, ss.status, ss.version
  FROM event_db.show_seats ss
 WHERE ss.id = 1;

SELECT bs.show_seat_id,
       COUNT(*)                              AS bookings_claiming_seat,
       GROUP_CONCAT(b.id      ORDER BY b.id) AS booking_ids,
       GROUP_CONCAT(b.user_id ORDER BY b.id) AS user_ids
  FROM booking_db.booking_seats bs
  JOIN booking_db.bookings b ON b.id = bs.booking_id
 GROUP BY bs.show_seat_id
HAVING COUNT(*) > 1;
```

Run as:

```bash
docker exec -e MYSQL_PWD=root bookmyseat-mysql mysql -uroot --table -e "<the SQL above>"
```

Output, verbatim:

```
+----------------+
| bookings_total |
+----------------+
|             10 |
+----------------+
+---------------------+
| booking_seats_total |
+---------------------+
|                  10 |
+---------------------+
+-----------------------------------+
| distinct_bookings_claiming_seat_1 |
+-----------------------------------+
|                                10 |
+-----------------------------------+
+--------------+---------+--------+---------+
| show_seat_id | show_id | status | version |
+--------------+---------+--------+---------+
|            1 |       1 | BOOKED |       0 |
+--------------+---------+--------+---------+
+--------------+------------------------+----------------------+-----------------------------+
| show_seat_id | bookings_claiming_seat | booking_ids          | user_ids                    |
+--------------+------------------------+----------------------+-----------------------------+
|            1 |                     10 | 1,2,3,4,5,6,7,8,9,10 | 22,1,50,41,20,46,39,6,18,14 |
+--------------+------------------------+----------------------+-----------------------------+
```

One physical seat, ten bookings, ten different users.

### What happened, and why

`BookingService.createBooking` does its work in two separate steps with nothing joining
them. First it asks event-service over HTTP for the seat map and checks that the requested
seat reads `AVAILABLE`; if it does not, the request is rejected with 409. Then — having
released nothing, locked nothing and recorded nothing — it inserts the booking rows and
afterwards tells event-service to mark the seat `BOOKED`.

Between that check and that write there is a window, and under a simultaneous burst the
window is wide open. All 50 requests read the seat map at effectively the same moment.
Ten of them read seat 1 as `AVAILABLE` before any of them had written anything, so all ten
passed the check and all ten went on to insert. The 40 that returned 409 were simply the
ones whose read landed after the seat had already been flipped to `BOOKED` — they were
late, not blocked.

Nothing downstream could catch the ten that got through. `booking_seats.show_seat_id` has
no unique constraint, so the database accepted ten rows pointing at the same seat without
complaint. The final "mark this seat BOOKED" call is a blind bulk `UPDATE` with no
`WHERE status = 'AVAILABLE'` clause, so each of the ten overwrote a value that was already
`BOOKED` and reported success. There is no lock, no Redis hold, no re-check inside the
transaction, and no constraint at any layer. The check and the write are two independent
operations, and being fast does not make them one.

### `show_seats.version` was still 0 — and that is the important detail

After ten separate bookings claimed seat 1, the row's `version` column read **0**.

`ShowSeat` carries a `@Version` column and the schema has it, so optimistic locking looks
like it is present. It is not being used. `ShowSeatRepository.markBooked` is a `@Modifying`
JPQL **bulk** `UPDATE`, and a bulk update bypasses `@Version` entirely: Hibernate does not
read the version, does not add it to the `WHERE` clause, and does not increment it. No
`OptimisticLockException` can be thrown, because no version is ever compared. The lock
exists in the schema and is never consulted.

**This is why P3.5 has to change the write path itself, not just add the annotation.** The
annotation is already there. Adding `@Version` to more places, or trusting the one that
exists, changes nothing while the write is a bulk `UPDATE`. The fix has to make the write
go through a path where the version is actually read and checked — a managed entity
update, or an explicit `WHERE version = :expected` / `WHERE status = 'AVAILABLE'` in the
statement — so that a second writer's update matches zero rows and fails. A `version` of 0
after ten writes is the direct evidence that today's write path never touches it.

### Nothing errored

Worth stating explicitly, because it is the whole character of this class of bug:

- All 10 successful requests returned **`201 Created`**. None returned a 5xx, and there
  were no transport failures (`status 0` count was 0).
- Every `UPDATE` matched its row. `InternalSeatService` logs at WARN when the number of
  rows updated differs from the number of seats requested; that warning never fired, so
  each of the ten blind updates found and changed exactly the row it asked for.
- No component logged a warning or an error during the run. The only WARN lines in either
  service log are Flyway's startup notice that MySQL 8.4 is newer than it has been tested
  against, emitted at 22:32:59 and 22:33:11 — both before the burst at 22:34:46.
- booking-service logged ten cheerful `CONFIRMED` lines, one per booking, all for
  `seats [1]`.

From inside any single request, nothing went wrong: the seat was free when it was read,
the insert succeeded, the update succeeded, the caller got a 201. Every component behaved
exactly as written. The violation does not exist inside any one request, any one row, or
any one log line — it exists only in the relationship *between* rows, and only a query
that looks across all of them can see it.
