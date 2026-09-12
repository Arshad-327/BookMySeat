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

---

## After — Redis Lua holds (layer 1)

**Date:** 2026-08-28 (k6 started at `2026-08-28T17:51:03Z`; the script releases every VU 3000 ms after that)

**Environment:** Windows 11 Home Single Language 10.0.26200 · AMD Ryzen 7 7840HS (8 cores / 16 logical) · 15.3 GB RAM · Temurin JDK 21.0.12.1 · MySQL 8.4.11 in Docker (`bookmyseat-mysql`, `--default-time-zone=+00:00`, host port 13306) · Docker Engine 29.6.1 · k6 v2.2.0 from `grafana/k6@sha256:5221b620a4f874faff6e32ba597aa667c058391fe4898b1c6f6377f062c6cdec` · services run as jars on the host, not in Compose.

New for this run and absent from the baseline: Redis, from `docker-compose.infra.yml`
(`bookmyseat-redis`, image `redis:7-alpine`, `--appendonly yes`). The environment line above
is the baseline's. This run did not re-capture versions, so the Redis patch version and the
k6 image digest were not recorded at run time and are not asserted here.

**Code under test:** commit `d33b2007bff74866b04e47031d3d9ebacfbff6ca` — *"Replace the naive
booking flow with Redis-backed seat holds"* — driven by the harness at
`c4148e5bcbc281cf156a781b71008ffc87cd8d06` — *"Point the contention harness at POST
/api/bookings/hold"*. `c4148e5` was `HEAD` when the burst fired. The two commits after
`d33b200` touch only `docs/load-test-results.md` and `load-tests/single-seat-contention.js`,
so the service code at `c4148e5` is exactly `d33b200`'s. To reproduce:

```bash
git checkout c4148e5
mvn -pl event-service,booking-service -am -DskipTests package
```

> The jars that ran were built by `mvn -q -pl event-service,booking-service -am -DskipTests
> package` at `2026-08-28T17:38:01Z`, from the working tree that was then committed unchanged
> as `d33b200` (`2026-08-28 23:12:57 +0530`); no source file was edited between that build
> and the commit. event-service was started from that build at `17:39:11Z`, booking-service
> on the default hold TTL at `17:49:20Z`, and neither was restarted before the burst.
>
> Every output below is copied from the raw tool output recorded in the Claude Code session
> that performed the run (transcript `357e2b96-cc13-40a0-9716-cf0f3a6880c7`), not from a
> summary of it and not from memory.

### Methodology

The baseline's steps 1–5, unchanged except for the endpoint (recorded above, before either
run was compared) and one deviation in the reset, described below. Infrastructure now
includes Redis:

```bash
docker compose -f docker-compose.infra.yml up -d mysql redis
```

**Warm-up** — the baseline's shape, aimed at `/hold` and including its 409 branch. Observed
output, verbatim:

```
--- warm event-service: 40x GET /api/shows/1/seats ---
done: 200
--- warm event-service: 10x GET /api/events ---
done
--- warm booking-service: 20x POST /api/bookings/hold (201 path, seats 41..60) ---
201 201 201 201 201 201 201 201 201 201 201 201 201 201 201 201 201 201 201 201 
--- warm booking-service: 10x POST /hold same held seat (409 path) ---
409 409 409 409 409 409 409 409 409 409 
--- warm booking-service: 10x GET /api/bookings/1 ---
warm-up complete
```

**Reset** — `./load-tests/reset-fixtures.sh`, which finished `READY` with `SHOW_ID 1`,
`SEAT_ID 1` and 60 of 60 seats available.

**Deviation — Redis had to be cleared by hand.** When this run was made, `reset-fixtures.sh`
reset MySQL and nothing else. After the warm-up and the reset, Redis still held hold keys
(seats 41–60 from the warm-up; 5 and 6 from earlier manual testing):

```
=== Redis hold keys left by the warm-up (reset-fixtures.sh does NOT clear these) ===
seat:hold:1:5 seat:hold:1:6 seat:hold:1:41 seat:hold:1:42 seat:hold:1:43 seat:hold:1:44 seat:hold:1:45 seat:hold:1:46 seat:hold:1:47 seat:hold:1:48 seat:hold:1:49 seat:hold:1:50 seat:hold:1:51 seat:hold:1:52 seat:hold:1:53 seat:hold:1:54 seat:hold:1:55 seat:hold:1:56 seat:hold:1:57 seat:hold:1:58 seat:hold:1:59 seat:hold:1:60 
count: 22
```

Seat 1 was not among them, so they could not have changed the result, but "identical known
state" was not true of Redis. They were removed with a targeted scan-and-delete — never
`FLUSHALL` — and the starting state was checked across both stores before the burst:

```bash
echo "=== targeted delete of seat:hold:* (NOT flushall - other keys are left alone) ==="
docker exec bookmyseat-redis sh -c "redis-cli --scan --pattern 'seat:hold:*' | xargs -r redis-cli DEL"
echo -n "seat:hold:* keys remaining: "; docker exec bookmyseat-redis redis-cli --scan --pattern 'seat:hold:*' | grep -c . || echo 0
echo -n "total keys in redis: "; docker exec bookmyseat-redis redis-cli DBSIZE
echo
echo "=== pre-run state confirmed ==="
echo -n "seat:hold:1:1 -> "; docker exec bookmyseat-redis redis-cli GET seat:hold:1:1; echo "(empty = free)"
docker exec -e MYSQL_PWD=root bookmyseat-mysql mysql -uroot --table -e \
  "SELECT COUNT(*) AS bookings FROM booking_db.bookings;
   SELECT COUNT(*) AS booking_seats FROM booking_db.booking_seats;
   SELECT id,status,version FROM event_db.show_seats WHERE id=1;"
```

Output, verbatim (`22` is the `DEL` reply; the second `0` is the `|| echo 0` fallback, printed
because `grep -c` exits non-zero when it counts nothing):

```
=== targeted delete of seat:hold:* (NOT flushall - other keys are left alone) ===
22
seat:hold:* keys remaining: 0
0
total keys in redis: 0

=== pre-run state confirmed ===
seat:hold:1:1 -> 
(empty = free)
+----------+
| bookings |
+----------+
|        0 |
+----------+
+---------------+
| booking_seats |
+---------------+
|             0 |
+---------------+
+----+-----------+---------+
| id | status    | version |
+----+-----------+---------+
|  1 | AVAILABLE |       0 |
+----+-----------+---------+
```

**The measured run — 50 VUs, straight at booking-service on port 8083:**

```bash
MSYS_NO_PATHCONV=1 docker run --rm -i \
  -v "$PWD/load-tests:/scripts" \
  -e BASE_URL=http://host.docker.internal:8083 \
  -e SHOW_ID=1 \
  -e SEAT_ID=1 \
  -e VUS=50 \
  grafana/k6 run /scripts/single-seat-contention.js 2>&1; echo "EXIT=$?"
```

| Parameter | Value (as printed by the script's own banner) |
|---|---|
| target | `http://host.docker.internal:8083/api/bookings/hold` |
| VUs | 50, one request each |
| `SHOW_ID` / `SEAT_ID` | 1 / 1 |
| users | 1..50 (distinct) |
| release | 3000 ms, all VUs at once |
| `JWT` | not set — no `Authorization` header sent |

### Validity — the requests genuinely collided

Checked before the status counts were read.

| | |
|---|---|
| Spread off the barrier (earliest → latest request) | **0 ms** (min 0 / avg 0 / max 0) |
| Minimum request duration | **78.21 ms** |

Every one of the 50 requests left the barrier inside a 0 ms window, while the *fastest*
response took 78.21 ms. No request could have finished before the last one was sent, so all
50 were inside the server together. That is a real collision, not a queue — the same test of
validity the baseline passed with 2 ms against 132.36 ms. The counts below are only worth
reading because of it.

### k6 summary, verbatim

```
  --------------------------------------------------------------
  SINGLE-SEAT CONTENTION - RAW RESULT
  --------------------------------------------------------------
  target                 http://host.docker.internal:8083/api/bookings/hold
  showId / seatId        1 / 1
  vus                    50

  REQUESTS
    total                50
    2xx                  1
    409                  49
    other / unlisted     0

  STATUS BREAKDOWN
    201                  1
    409                  49

  BURST TIGHTNESS (how far after the release instant each request went out)
    min / avg / max      0 / 0 / 0 ms
    spread               0 ms
    A wide spread means the VUs did not really collide. Raise START_DELAY_MS.

  LATENCY (http_req_duration, ms)
    min / avg / med      78.21 / 161.62 / 152.84
    p95 / p99 / max      258.94 / 263.38 / 264.31

  --------------------------------------------------------------
  These counts are HTTP responses only, and confirm was never called.
  A 201 here means the caller was told it owns the seat; whether exactly
  one caller was told that is a question for the data, not this summary.
  Run the verification query in load-tests/README.md against booking_db,
  and check the seat:hold key in Redis, to find out.
  --------------------------------------------------------------

running (0m03.3s), 00/50 VUs, 50 complete and 0 interrupted iterations
single_seat_burst ✓ [ 100% ] 50 VUs  0m03.3s/2m0s  50/50 iters, 1 per VU
EXIT=0
```

k6 exited `0` (the `EXIT=0` line is the command's own `echo`). It sets no thresholds by
design, so that exit code carries no verdict.

The latency figures are recorded for completeness and are **not** compared with the
baseline's: they come from a different endpoint doing different work, and this run exists to
measure mutual exclusion, not speed.

### Ground truth in the database

Read immediately after the burst, in the same command as the Redis read below.

```sql
SELECT COUNT(*) AS bookings_total FROM booking_db.bookings;

SELECT COUNT(*) AS booking_seats_total FROM booking_db.booking_seats;

SELECT COUNT(DISTINCT bs.booking_id) AS distinct_bookings_claiming_seat_1
  FROM booking_db.booking_seats bs
 WHERE bs.show_seat_id = 1;

SELECT b.id, b.user_id, b.status, b.expires_at
  FROM booking_db.bookings b ORDER BY b.id;

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
echo "(an empty result for the last query = no seat claimed more than once)"
```

Output, verbatim:

```
+----------------+
| bookings_total |
+----------------+
|              1 |
+----------------+
+---------------------+
| booking_seats_total |
+---------------------+
|                   1 |
+---------------------+
+-----------------------------------+
| distinct_bookings_claiming_seat_1 |
+-----------------------------------+
|                                 1 |
+-----------------------------------+
+----+---------+---------+----------------------------+
| id | user_id | status  | expires_at                 |
+----+---------+---------+----------------------------+
|  2 |      48 | PENDING | 2026-08-28 18:01:06.216338 |
+----+---------+---------+----------------------------+
+--------------+---------+-----------+---------+
| show_seat_id | show_id | status    | version |
+--------------+---------+-----------+---------+
|            1 |       1 | AVAILABLE |       0 |
+--------------+---------+-----------+---------+
(an empty result for the last query = no seat claimed more than once)
```

The last query — every seat claimed by more than one booking — printed no table at all: the
`mysql` client prints nothing for an empty result set, and the line after it is the command's
own `echo`. In the baseline the same query returned one row naming ten bookings.

### Ground truth in Redis

New relative to the baseline, which had no Redis. Read in the same command as the queries
above:

```bash
echo -n "keys matching seat:hold:1:1 -> "; docker exec bookmyseat-redis redis-cli --scan --pattern 'seat:hold:1:1' | tr '\n' ' '; echo
echo -n "count for that seat            -> "; docker exec bookmyseat-redis redis-cli --scan --pattern 'seat:hold:1:1' | grep -c . || echo 0
echo -n "GET seat:hold:1:1              -> "; docker exec bookmyseat-redis redis-cli GET seat:hold:1:1
echo -n "TTL seat:hold:1:1              -> "; docker exec bookmyseat-redis redis-cli TTL seat:hold:1:1
echo -n "ALL seat:hold:* keys           -> "; docker exec bookmyseat-redis redis-cli --scan --pattern 'seat:hold:*' | tr '\n' ' '; echo
```

Output, verbatim:

```
keys matching seat:hold:1:1 -> seat:hold:1:1 
count for that seat            -> 1
GET seat:hold:1:1              -> 2
TTL seat:hold:1:1              -> 579
ALL seat:hold:* keys           -> seat:hold:1:1 
```

- **Key count: 1.** Exactly one hold exists for seat 1, and it is the only `seat:hold:*` key in
  Redis.
- **`GET` value: `2`.** A hold's value is the id of the booking that owns it.
- **It matches the one surviving booking.** `booking_db.bookings` contains exactly one row, and
  its `id` is `2` (`user_id` 48, `PENDING`). Redis and MySQL independently name the same single
  winner; there is no second claimant in either store.

### Before and after

| | Baseline — naive (`2b4d82c`) | After — Redis Lua holds (`d33b200`) |
|---|---|---|
| Endpoint | `POST /api/bookings` | `POST /api/bookings/hold` |
| Concurrent requests for one seat | 50 | 50 |
| Spread off the barrier | 2 ms | 0 ms |
| Minimum request duration | 132.36 ms | 78.21 ms |
| **Successful claims (201)** | **10** | **1** |
| 409 | 40 | 49 |
| Any other status (5xx, transport failure, unlisted) | 0 | 0 |
| `distinct_bookings_claiming_seat_1` | 10 | 1 |
| Seats claimed by more than one booking | seat 1, by bookings 1–10 | none |
| `seat:hold:1:1` in Redis | — (no Redis) | 1 key, value `2` |
| `event_db.show_seats` id 1 | `BOOKED`, version 0 | `AVAILABLE`, version 0 |

The correct number of successful claims on one seat is one. The naive implementation produced
ten. This one produced one.

### Seat 1 is still `AVAILABLE` with version 0 — and that is correct

After the run, `event_db.show_seats` id 1 reads `AVAILABLE`, `version` 0 — exactly what it
read before the burst. **That is not a leftover and not a missed write.** Confirm was never
called (deliberately; see the endpoint note above), and nothing else writes that row.

The hold lives only in Redis, as `seat:hold:1:1`. It is never mirrored into the database, and
that is the P2.1 decision: there is deliberately **no `HELD` status in the database**. The
column has exactly two values — see the comment on `show_seats.status` in
`event-service/src/main/resources/db/migration/V1__initial_schema.sql` and the javadoc on
event-service's `SeatStatus` enum. A hold is temporary state that must expire on its own;
Redis expires it for free, whereas a `HELD` row would need a sweeper and could be left stuck
by an abandoned checkout.

The two stores answer different questions. `show_seats.status` answers *"is this seat
sold?"*, which is permanent — and seat 1 is not sold, because nobody has paid for it.
*"Can this seat be sold to someone else right now?"* is ephemeral, and it is answered by
Redis — and the answer is no, because booking 2 holds it. `AVAILABLE` with a live hold is the
correct state of a seat in checkout. It becomes `BOOKED`, and `version` moves 0 → 1, only when
the booking is confirmed.

### The 49 losers got a clean 409 — nothing else

- `STATUS BREAKDOWN` lists exactly two codes: `201` once and `409` 49 times. The script counts
  status `0` (transport failure) and `500`–`504` as named counters and prints every counter
  that is non-zero, so their absence means **zero 500s and zero transport failures**.
  `other / unlisted` is `0`.
- Every request that lost the race was refused with the conflict status, not failed. **The
  fix did not trade a correctness bug for an availability one:** no request errored, timed
  out or dropped its connection in exchange for the double-sale going away.
- Limit of this evidence: per-VU logging is off by default, so the 409 response bodies were
  not captured in this run. This section asserts the status codes, not the body contents.

---

## Overlapping seats — all-or-nothing acquisition

**Date:** 2026-09-12 (burst fired at `2026-09-12T15:20:04Z` / `20:50:04+05:30`)

**Environment:** Windows 11 Home Single Language 10.0.26200 · AMD Ryzen 7 7840HS w/ Radeon 780M (8 cores / 16 logical) · 15.3 GB RAM · Temurin JDK 21.0.12.1+1 · MySQL 8.4.11 in Docker (`bookmyseat-mysql`, `@@global.time_zone = +00:00`, host port 13306) · Redis 7.4.11 in Docker (`bookmyseat-redis`) · Docker Engine 29.6.1 · k6 v2.2.0 from `grafana/k6@sha256:5221b620a4f874faff6e32ba597aa667c058391fe4898b1c6f6377f062c6cdec` · services run as jars on the host, not in Compose.

**Code under test:** commit `8ea7dfc187c4300e1f5fe70833471aa650bc3b31` — *"Add the overlapping-seats
burst and an independent verifier for it"*. `HEAD` when the burst fired; the working tree
carried no modification to any tracked file, so the jars are exactly that commit. To
reproduce:

```bash
git checkout 8ea7dfc
mvn -pl event-service,booking-service -am -DskipTests package
```

> `booking_db` was at schema version 1 when booking-service started for this run, so Flyway
> applied `V2__unique_booking_constraints.sql` at startup: *"Migrating schema `booking_db` to
> version 2 - unique booking constraints"*, then *"Successfully applied 1 migration ... now at
> version v2 (execution time 00:00.219s)"*, recorded in `flyway_schema_history` with
> `success = 1`. The only WARN in either service log is Flyway's notice that MySQL 8.4 is
> newer than it has been tested against, emitted at startup, before the burst.

### Methodology

The layer-1 run's steps, unchanged, with the burst script swapped. The warm-up ran **before**
the reset and in the same shape as the earlier runs (40 × `GET /api/shows/1/seats`, 10 ×
`GET /api/events`, 20 × `POST /hold` on seats 41–60, 10 × `POST /hold` on an already-held
seat, 10 × `GET /api/bookings/1`), producing 20 × `201` then 10 × `409` — both branches
compiled before anything was measured. `./load-tests/reset-fixtures.sh` then truncated both
databases, deleted the 20 holds the warm-up had left, re-seeded `event_db` and reported
`HOLDS 0` with 60 of 60 seats available.

```bash
MSYS_NO_PATHCONV=1 docker run --rm -i \
  -v "$PWD/load-tests:/scripts" \
  -e BASE_URL=http://host.docker.internal:8083 \
  -e EVENT_SERVICE_URL=http://host.docker.internal:8082 \
  -e SHOW_ID=1 \
  -e PAIRS=20 \
  grafana/k6 run /scripts/overlapping-seats.js \
  2> k6-stderr.txt | tee overlapping-run.txt

./load-tests/verify-overlapping.sh overlapping-run.txt
```

| Parameter | Value (as printed by the script's own banner) |
|---|---|
| target | `http://host.docker.internal:8083/api/bookings/hold` |
| pairs / VUs | 20 / 40, one request each |
| `SHOW_ID` | 1 |
| seats | 1..60 in disjoint triples; A wants {X,Y}, B wants {Y,Z} |
| users | 1..40 (distinct) |
| release | 3000 ms, all VUs at once |

Confirm is never called, exactly as in `single-seat-contention.js`.

### Validity — the requests genuinely collided

Checked before the verdicts were read.

| | |
|---|---|
| Spread off the barrier (earliest → latest request) | **3 ms** (min 0 / avg 0.88 / max 3) |
| Minimum hold request duration | **93.47 ms** |

All 40 requests left the barrier inside a 3 ms window while the *fastest* response took
93.47 ms, so the last request was in flight long before the first could return and every
pair was inside the server together. That is a real collision, not a queue — the same test
of validity the earlier runs passed with 2 ms against 132.36 ms and 0 ms against 78.21 ms.
A spread comparable to the service time would have made this sequential traffic, and the
correct response would have been to raise `START_DELAY_MS` and re-run, not to report the
verdicts.

Latency for completeness, and **not** compared with the earlier runs — a different script
against a different request shape: min / avg / med 93.47 / 235.13 / 246.66 ms, p95 / p99 /
max 309.34 / 310.76 / 310.83 ms.

HTTP counts: 40 requests, 20 × `201`, 20 × `409`, `other / unlisted` 0 — so zero 5xx and
zero transport failures. Every 409 named exactly the one seat its pair contested, and no
409 named a seat its caller had not requested (`unrequested=0` on all 40 result lines).

### The verdict — `verify-overlapping.sh`, verbatim

```
== Reading the plan and the reported HTTP outcomes from overlapping-run.txt
  20 pairs on show 1, 40 requests reported

== Reading every seat:hold:* key and its value from bookmyseat-redis
  40 hold key(s)

== Reading bookings and booking_seats from booking_db in bookmyseat-mysql
  20 booking(s)

== Verdicts
  PAIR  A user {seats}   B user {seats}   WINNER  BOOKING  FREE   VERDICT
  1     1 {1,2}          2 {2,3}          B       13       1      PASS
  2     3 {4,5}          4 {5,6}          A       10       6      PASS
  3     5 {7,8}          6 {8,9}          B       23       7      PASS
  4     7 {10,11}        8 {11,12}        B       2        10     PASS
  5     9 {13,14}        10 {14,15}       A       1        15     PASS
  6     11 {16,17}       12 {17,18}       A       30       18     PASS
  7     13 {19,20}       14 {20,21}       B       31       19     PASS
  8     15 {22,23}       16 {23,24}       A       19       24     PASS
  9     17 {25,26}       18 {26,27}       A       15       27     PASS
  10    19 {28,29}       20 {29,30}       A       12       30     PASS
  11    21 {31,32}       22 {32,33}       B       9        31     PASS
  12    23 {34,35}       24 {35,36}       A       28       36     PASS
  13    25 {37,38}       26 {38,39}       A       34       39     PASS
  14    27 {40,41}       28 {41,42}       B       20       40     PASS
  15    29 {43,44}       30 {44,45}       B       5        43     PASS
  16    31 {46,47}       32 {47,48}       A       6        48     PASS
  17    33 {49,50}       34 {50,51}       B       3        49     PASS
  18    35 {52,53}       36 {53,54}       B       8        52     PASS
  19    37 {55,56}       38 {56,57}       B       11       55     PASS
  20    39 {58,59}       40 {59,60}       B       4        58     PASS

  pairs passing      20 of 20
  orphaned holds     0
  hold keys checked  40

ALL VERDICTS HOLD: in every pair exactly one user holds their complete set,
the other got a 409, and no seat is held by a booking that did not survive.
```

**The verifier exited `0`.** Unlike k6's exit code, this one carries the verdict: the script
exits `0` only when every pair passes and no hold is orphaned, `1` when a check fails, and
`2` when the run could not be graded at all.

### Ground truth in Redis

Every `seat:hold:*` key with its value and remaining TTL, read 22 seconds after the burst:

```
seat:hold:1:2        13     ttl=578
seat:hold:1:3        13     ttl=578
seat:hold:1:4        10     ttl=578
seat:hold:1:5        10     ttl=578
seat:hold:1:8        23     ttl=578
seat:hold:1:9        23     ttl=578
seat:hold:1:11       2      ttl=578
seat:hold:1:12       2      ttl=578
seat:hold:1:13       1      ttl=578
seat:hold:1:14       1      ttl=578
seat:hold:1:16       30     ttl=578
seat:hold:1:17       30     ttl=578
seat:hold:1:20       31     ttl=578
seat:hold:1:21       31     ttl=578
seat:hold:1:22       19     ttl=578
seat:hold:1:23       19     ttl=578
seat:hold:1:25       15     ttl=578
seat:hold:1:26       15     ttl=578
seat:hold:1:28       12     ttl=578
seat:hold:1:29       12     ttl=578
seat:hold:1:32       9      ttl=578
seat:hold:1:33       9      ttl=578
seat:hold:1:34       28     ttl=578
seat:hold:1:35       28     ttl=578
seat:hold:1:37       34     ttl=578
seat:hold:1:38       34     ttl=578
seat:hold:1:41       20     ttl=578
seat:hold:1:42       20     ttl=578
seat:hold:1:44       5      ttl=578
seat:hold:1:45       5      ttl=578
seat:hold:1:46       6      ttl=578
seat:hold:1:47       6      ttl=578
seat:hold:1:50       3      ttl=578
seat:hold:1:51       3      ttl=578
seat:hold:1:53       8      ttl=578
seat:hold:1:54       8      ttl=578
seat:hold:1:56       11     ttl=578
seat:hold:1:57       11     ttl=578
seat:hold:1:59       4      ttl=578
seat:hold:1:60       4      ttl=578
total seat:hold:* keys -> 40
DBSIZE                 -> 40
```

Forty keys for twenty winners — two seats each, and every key's value is the id of the
booking that owns it. `DBSIZE` equals the hold count, so no other key was left anywhere in
Redis.

### Ground truth in MySQL

```
+----+---------+---------+---------+----------------------------+----------------------------+-------+-----------+
| id | user_id | show_id | status  | expires_at                 | created_at                 | seats | sold      |
+----+---------+---------+---------+----------------------------+----------------------------+-------+-----------+
|  1 |       9 |       1 | PENDING | 2026-09-12 15:30:04.598196 | 2026-09-12 15:20:04.600344 | 13,14 | NULL,NULL |
|  2 |       8 |       1 | PENDING | 2026-09-12 15:30:04.598196 | 2026-09-12 15:20:04.600304 | 11,12 | NULL,NULL |
|  3 |      34 |       1 | PENDING | 2026-09-12 15:30:04.648647 | 2026-09-12 15:20:04.650076 | 50,51 | NULL,NULL |
|  4 |      40 |       1 | PENDING | 2026-09-12 15:30:04.648647 | 2026-09-12 15:20:04.650051 | 59,60 | NULL,NULL |
|  5 |      30 |       1 | PENDING | 2026-09-12 15:30:04.682906 | 2026-09-12 15:20:04.685338 | 44,45 | NULL,NULL |
|  6 |      31 |       1 | PENDING | 2026-09-12 15:30:04.682906 | 2026-09-12 15:20:04.685338 | 46,47 | NULL,NULL |
|  8 |      36 |       1 | PENDING | 2026-09-12 15:30:04.598196 | 2026-09-12 15:20:04.600774 | 53,54 | NULL,NULL |
|  9 |      22 |       1 | PENDING | 2026-09-12 15:30:04.598196 | 2026-09-12 15:20:04.600937 | 32,33 | NULL,NULL |
| 10 |       3 |       1 | PENDING | 2026-09-12 15:30:04.598196 | 2026-09-12 15:20:04.600307 | 4,5   | NULL,NULL |
| 11 |      38 |       1 | PENDING | 2026-09-12 15:30:04.608033 | 2026-09-12 15:20:04.609765 | 56,57 | NULL,NULL |
| 12 |      19 |       1 | PENDING | 2026-09-12 15:30:04.603574 | 2026-09-12 15:20:04.605429 | 28,29 | NULL,NULL |
| 13 |       2 |       1 | PENDING | 2026-09-12 15:30:04.602474 | 2026-09-12 15:20:04.604236 | 2,3   | NULL,NULL |
| 15 |      17 |       1 | PENDING | 2026-09-12 15:30:04.726741 | 2026-09-12 15:20:04.728684 | 25,26 | NULL,NULL |
| 19 |      15 |       1 | PENDING | 2026-09-12 15:30:04.756108 | 2026-09-12 15:20:04.758121 | 22,23 | NULL,NULL |
| 20 |      28 |       1 | PENDING | 2026-09-12 15:30:04.764092 | 2026-09-12 15:20:04.767013 | 41,42 | NULL,NULL |
| 23 |       6 |       1 | PENDING | 2026-09-12 15:30:04.764092 | 2026-09-12 15:20:04.767078 | 8,9   | NULL,NULL |
| 28 |      23 |       1 | PENDING | 2026-09-12 15:30:04.808900 | 2026-09-12 15:20:04.811618 | 34,35 | NULL,NULL |
| 30 |      11 |       1 | PENDING | 2026-09-12 15:30:04.815839 | 2026-09-12 15:20:04.817939 | 16,17 | NULL,NULL |
| 31 |      14 |       1 | PENDING | 2026-09-12 15:30:04.816530 | 2026-09-12 15:20:04.817952 | 20,21 | NULL,NULL |
| 34 |      25 |       1 | PENDING | 2026-09-12 15:30:04.821830 | 2026-09-12 15:20:04.823329 | 37,38 | NULL,NULL |
+----+---------+---------+---------+----------------------------+----------------------------+-------+-----------+
+----------------+
| bookings_total |
+----------------+
|             20 |
+----------------+
+---------------------+
| booking_seats_total |
+---------------------+
|                  40 |
+---------------------+
+-----------+----+
| status    | n  |
+-----------+----+
| AVAILABLE | 60 |
+-----------+----+
(an empty result for the 'claiming > 1' query = no seat claimed more than once)
```

Twenty bookings, forty `booking_seats` rows, and the "seats claimed by more than one
booking" query returned no rows at all. `sold_show_seat_id` is `NULL` on every row because
confirm was never called — the column is written only by `Booking.confirm()`. All 60
`show_seats` rows for show 1 still read `AVAILABLE`, which is correct: a hold lives only in
Redis, and nothing here was sold.

### Why this test exists, and what the single-seat test cannot prove

`single-seat-contention.js` proves that concurrent claims on **one** seat are mutually
exclusive. That is a real property, but it is a weak one, because **a naive implementation
passes it.** A loop issuing one `SET NX` per seat is perfectly exclusive for a single key:
fifty users fighting over one seat would still produce exactly one winner, and the
single-seat run would look identical.

What such a loop cannot do is take **several** seats all or nothing. Two requests that
overlap on one seat can each win part of what they asked for:

```
A wants {X, Y}            B wants {Y, Z}
A: SET X NX -> ok
                          B: SET Z NX -> ok
A: SET Y NX -> ok
                          B: SET Y NX -> fails
```

B is now refused and its booking rolls back — but B already took Z. Nothing rolls Z back:
it is a Redis key, not a database row, so the transaction rollback does not touch it. Z
becomes an **orphaned hold: unbookable for the full ten minutes of its TTL, and owned by
nobody.** No booking exists that can confirm it, no user knows they have it, and no query
against `booking_db` can see anything wrong. The seat is simply gone from the inventory
until the TTL lapses. Only a test in which requests overlap can tell the two implementations
apart, which is exactly why this one exists: it is the test that justifies `hold_seats.lua`
being **one** atomic script rather than a loop, making acquire-or-undo a single indivisible
step.

**The twenty freed seats are the evidence.** In each pair the loser asked for two seats, one
contested and one uncontested, and lost. Every one of those twenty uncontested seats came
back free:

```
seats 1, 6, 7, 10, 15, 18, 19, 24, 27, 30, 31, 36, 39, 40, 43, 48, 49, 52, 55, 58
```

That is the `FREE` column of the verdict table, one seat per pair, and none of them appears
in the Redis listing above. Forty keys held by twenty winners plus these twenty free seats
accounts for all 60 seats in the show. Under the naive loop these are precisely the seats
that would have been orphaned — each held by a booking that was rolled back. `orphaned
holds 0` is the same fact stated from the other direction: the verifier checked every one
of the 40 surviving keys and found each owned by a booking that exists, is `PENDING`, and
claims that seat.

### The verifier is a separate program, and that is the point

`overlapping-seats.js` sets no thresholds and passes no verdict. It reports what it was told
over HTTP and stops there. `verify-overlapping.sh` is a different program in a different
language, and it decides from the stores themselves — Redis for who holds each seat, MySQL
for which bookings survived.

That separation is what makes this evidence rather than a claim. A `201` means the caller
*was told* it owns its seats; it is not proof that it does. A script that grades its own run
can only ever confirm that the service is internally consistent with itself — the same
reason the single-seat numbers were checked against SQL instead of read off the k6 summary.
The verifier takes exactly two things from the k6 output, the plan and the reported HTTP
outcomes, and treats both as claims to be checked: verdict 3 exists specifically to catch
the case where the stores and the HTTP response disagree, failing the pair if the winner was
told a booking id that `booking_db` and Redis do not confirm. Every pair passing means the
two accounts agree, which is a strictly stronger statement than either one alone.

### All forty requests allocated a booking id — including six invisible in the table above

Twenty bookings survive and the highest surviving id is 34, which leaves 14 ids missing
below the maximum. Those 14 are rolled-back inserts, expected: MySQL does not return an
auto-increment value to the pool when a transaction rolls back. But 20 winners plus 14
rolled-back inserts is only 34 of the 40 requests, and the remaining six appear never to
have allocated an id at all — which would mean they were refused before reaching the
`INSERT`.

They were not. **All forty requests allocated an id; the burst consumed ids 1 through 40.**
The six are bookings 35–40, and they are invisible in the table only because their ids are
*higher* than the highest surviving id, so nothing marks their absence. The booking-service
log names all six:

```
booking 35 could not hold seat(s) [5] on show 1 - already held
booking 36 could not hold seat(s) [56] on show 1 - already held
booking 38 could not hold seat(s) [20] on show 1 - already held
booking 37 could not hold seat(s) [41] on show 1 - already held
booking 40 could not hold seat(s) [38] on show 1 - already held
booking 39 could not hold seat(s) [47] on show 1 - already held
```

Each names its pair's contested seat, and each maps to a losing side in the plan: 35 to pair
2 B, 36 to pair 19 A, 37 to pair 14 A, 38 to pair 7 A, 39 to pair 16 B, 40 to pair 13 B.
Two independent confirmations: the burst window of the log holds exactly 40 lines carrying
40 distinct booking ids, 1–40 with no gaps (20 `PENDING` and 20 `could not hold`), and
`bookings.AUTO_INCREMENT` reads **41** after the run, so exactly 40 values were consumed
since the reset truncated the table.

So there is no third code path. Every request took the same one, and the ordering is
deliberate: `BookingService.hold()` saves the `PENDING` booking **first** and takes the holds
**second**, because the booking id is the hold's value — a hold cannot be written before the
id it stores exists. All 40 requests therefore reach `bookingRepository.save()` and allocate
an id; the 20 that then lose the Lua script throw `SeatsAlreadyHeldException`, the
transaction rolls back, and the id is burnt. The apparent gap was an artifact of inferring
allocation from `MAX(id)` of the survivors, which cannot see ids burnt above the maximum.
