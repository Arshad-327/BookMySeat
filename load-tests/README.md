# Load tests

k6 scripts for BookMySeat. **No k6 installation required** — everything runs from
the `grafana/k6` Docker image.

| Script | What it does |
|---|---|
| `single-seat-contention.js` | Fires N virtual users at one seat at the same instant and reports the status breakdown |

---

## What `single-seat-contention.js` does

Every VU sends exactly one `POST /api/bookings` for the same `showId` + `seatId`.
All of them block on a shared release instant computed in `setup()`, so they fire
together rather than queueing.

It **counts and prints**: total requests, 2xx, 409, and every other status seen —
including status `0`, which is k6's marker for a transport failure (connection
refused, timeout, reset) rather than an HTTP response.

It **does not assert anything and sets no thresholds**, by design. k6 always exits
`0`. A 409 is not a failure and a 201 is not a success — what they mean depends on
what is in `booking_db` afterwards.

> **The HTTP status counts are not the result.**
> booking-service can return 201 to several callers for the same seat and log
> nothing at all, because from each request's point of view nothing went wrong.
> The authoritative answer is the [verification query](#4-check-what-actually-happened)
> below. Read the counts, then count the rows.

---

## Before you run

### 1. Start the services

The script talks to **booking-service** only; booking-service calls event-service
itself.

```bash
docker compose -f docker-compose.infra.yml up -d
mvn -pl event-service -am -DskipTests package
mvn -pl booking-service -am -DskipTests package

java -jar event-service/target/event-service-0.0.1-SNAPSHOT.jar --spring.profiles.active=demo
java -jar booking-service/target/booking-service-0.0.1-SNAPSHOT.jar
```

Confirm both are up:

```bash
curl -s localhost:8082/actuator/health
curl -s localhost:8083/actuator/health
```

### 2. Pick a seat that is actually AVAILABLE

A seat that is already `BOOKED` makes every request a 409 and the run tells you
nothing. List the free ones:

Ask event-service, not booking-service — booking-service has no seat-map endpoint:

```bash
curl -s localhost:8082/api/shows/1/seats \
  | python -c "import sys,json;d=json.load(sys.stdin);print([s['id'] for r in d['rows'] for s in r['seats'] if s['status']=='AVAILABLE'][:20])"
```

Take any id from that list as `SEAT_ID`.

### 3. Reset between runs

A seat can only be won once, so a second run against the same seat is a different
experiment. Reset to one identical, known state before **every** run:

```bash
./load-tests/reset-fixtures.sh
```

It needs Docker, `java` on the `PATH`, and the event-service jar built
(`mvn -pl event-service -am -DskipTests package`). In order, it:

- starts `bookmyseat-mysql` and `bookmyseat-redis` if they are down, and waits for
  both to be healthy
- truncates every application table in `booking_db` and `event_db` — never `auth_db`,
  never `flyway_schema_history` — which also resets `AUTO_INCREMENT`, so ids come out
  identical on every run
- deletes every `seat:hold:*` and `idem:*` key in Redis with a targeted scan and
  delete — never `FLUSHALL`, because Redis also holds keys that belong to other
  concerns
- re-seeds `event_db` by running event-service's real `DemoDataSeeder` in a throwaway
  headless JVM, so a running event-service needs no restart
- verifies the first seat is `AVAILABLE`, `booking_db` is empty and **zero**
  `seat:hold:*` and **zero** `idem:*` keys remain, and exits non-zero naming the
  problem if any check fails

It finishes with a `READY` block giving the `SHOW_ID` and `SEAT_ID` to use.

Redis is part of the reset, not an extra. A hold is a Redis key and nothing else —
there is no `HELD` status in the database — so a hold left over from an earlier run
passes every MySQL check and still makes `POST /api/bookings/hold` return 409 for that
seat. An `idem:*` key is the same gap one layer up: it names a booking id that the
truncate has just deleted and `AUTO_INCREMENT` is about to reissue, so a leftover one
would resolve a replay to a booking from a previous run. There are two such spaces,
`idem:hold:<uuid>` and `idem:confirm:<uuid>` — one per endpoint, so a key sent to both
is two independent records — and the `idem:*` glob clears both.

Destructive and local-dev only. Do not run it while a load test or other traffic is
hitting booking-service: a hold written mid-reset makes the final check fail.

### 4. Get a JWT (optional today)

```bash
curl -s -X POST localhost:8081/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","password":"your-password"}'
```

Use the `accessToken` value as `JWT`.

> **booking-service does not check the JWT.** It has no security filter; the
> caller's identity comes from the `X-User-Id` header, which is currently taken
> entirely on trust. The script sends the token as a `Bearer` header anyway so it
> keeps working unchanged once api-gateway starts validating it, and it derives
> `X-User-Id` from the token's `sub` claim. If `JWT` is unset, `X-User-Id` falls
> back to `USER_ID` (default `1`) and no `Authorization` header is sent — the run
> still works.

---

## Running it

The image's entrypoint is already `k6`, so the command is `run <script>`. Mount
this directory to `/scripts` and pass configuration with `-e`.

### macOS and Windows (Docker Desktop)

`host.docker.internal` resolves to the host automatically.

```bash
docker run --rm -i \
  -v "$PWD/load-tests:/scripts" \
  -e BASE_URL=http://host.docker.internal:8083 \
  -e SHOW_ID=1 \
  -e SEAT_ID=20 \
  -e JWT="$JWT" \
  grafana/k6 run /scripts/single-seat-contention.js
```

On **Windows PowerShell**, swap the line-continuations and quoting:

```powershell
docker run --rm -i `
  -v "${PWD}\load-tests:/scripts" `
  -e BASE_URL=http://host.docker.internal:8083 `
  -e SHOW_ID=1 `
  -e SEAT_ID=20 `
  -e JWT="$env:JWT" `
  grafana/k6 run /scripts/single-seat-contention.js
```

### Linux

`host.docker.internal` does **not** exist by default. Two ways to fix it — pick one.

**Option A — map the hostname (recommended, Docker 20.10+).** Keeps the command
identical to macOS apart from one added flag:

```bash
docker run --rm -i \
  --add-host=host.docker.internal:host-gateway \
  -v "$PWD/load-tests:/scripts" \
  -e BASE_URL=http://host.docker.internal:8083 \
  -e SHOW_ID=1 \
  -e SEAT_ID=20 \
  -e JWT="$JWT" \
  grafana/k6 run /scripts/single-seat-contention.js
```

**Option B — share the host network.** Linux only; `--network host` is ignored on
Docker Desktop. The container then reaches services on plain `localhost`:

```bash
docker run --rm -i \
  --network host \
  -v "$PWD/load-tests:/scripts" \
  -e BASE_URL=http://localhost:8083 \
  -e SHOW_ID=1 \
  -e SEAT_ID=20 \
  -e JWT="$JWT" \
  grafana/k6 run /scripts/single-seat-contention.js
```

Option B removes one network hop, which is marginally better for a timing-
sensitive burst. Option A is more portable.

### If the services run inside Compose

Then they are not on the host at all — join their network and use service names:

```bash
docker run --rm -i \
  --network bookmyseat_bookmyseat-net \
  -v "$PWD/load-tests:/scripts" \
  -e BASE_URL=http://booking-service:8083 \
  -e SHOW_ID=1 -e SEAT_ID=20 -e JWT="$JWT" \
  grafana/k6 run /scripts/single-seat-contention.js
```

Check the network name with `docker network ls`.

---

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `BASE_URL` | `http://host.docker.internal:8083` | booking-service root. **No trailing slash.** |
| `SHOW_ID` | `1` | Show to book against |
| `SEAT_ID` | `1` | The single `show_seats` id every VU fights over |
| `JWT` | *(empty)* | Access token. Sent as `Bearer`; ignored by booking-service today |
| `VUS` | `50` | Virtual users, one request each |
| `USER_ID` | JWT `sub`, else `1` | Base value for `X-User-Id` |
| `DISTINCT_USERS` | `true` | Each VU books as `USER_ID + n`. `false` = every request from one user |
| `START_DELAY_MS` | `3000` | Time between setup and the release instant. Must exceed VU spawn time |
| `SPIN_MS` | `25` | Length of the busy-spin at the end of the wait |
| `VERBOSE` | `false` | `true` prints one line per VU with status, offset and body |

> **`Idempotency-Key` is required by `POST /api/bookings/hold`, and the scripts
> send it for you.** Each VU generates a fresh UUID, so there is nothing to
> configure — and nothing to reuse. A key is only a replay if it is *repeated*:
> two VUs sharing one would make the second a replay of the first, answered
> `200` with the first VU's booking before the seat hold was ever attempted, and
> the run would measure idempotency instead of contention. If you drive `/hold`
> by hand with `curl`, send a fresh `-H 'Idempotency-Key: <uuid>'` per attempt;
> omitting it is a `400`, and a non-UUID value is also a `400`.

---

## Reading the output

```
  REQUESTS
    total                <n>
    2xx                  <n>
    409                  <n>
    other / unlisted     <n>

  STATUS BREAKDOWN
    ...

  BURST TIGHTNESS (how far after the release instant each request went out)
    min / avg / max      ... ms
    spread               ... ms
```

**Check `spread` first.** It is the gap between the earliest and latest request
leaving the barrier. If it is wide relative to how long a booking takes to
process, the VUs did not overlap and the run is not a contention test at all —
raise `START_DELAY_MS` and repeat before drawing any conclusion from the status
counts.

`status 0` in the breakdown means requests never got an HTTP response. Check that
`BASE_URL` is reachable from inside the container and that booking-service is not
saturated, before reading anything into the other counts.

---

## 4. Check what actually happened

This is the real result. Run it after every run:

```bash
docker exec bookmyseat-mysql mysql -uroot -proot booking_db -e "
SELECT bs.show_seat_id,
       COUNT(*)                              AS bookings_claiming_seat,
       GROUP_CONCAT(b.id      ORDER BY b.id) AS booking_ids,
       GROUP_CONCAT(b.user_id ORDER BY b.id) AS user_ids
FROM booking_seats bs
JOIN bookings b ON b.id = bs.booking_id
GROUP BY bs.show_seat_id
HAVING COUNT(*) > 1;"
```

Every row is one physical seat claimed by more than one booking. An empty result
means no seat was multiply claimed **in this run**; it is not proof that the code
prevents it.

Cross-check against what event-service believes:

```bash
curl -s localhost:8082/api/shows/1/seats \
  | python -c "import sys,json;d=json.load(sys.stdin);print([(s['id'],s['status']) for r in d['rows'] for s in r['seats'] if s['id']==20])"
```

And the totals either side:

```bash
docker exec bookmyseat-mysql mysql -uroot -proot booking_db -e \
  "SELECT COUNT(*) AS bookings FROM bookings; SELECT COUNT(*) AS booking_seats FROM booking_seats;"
```

---

## Keeping results comparable

This script exists to measure a change, so record the conditions alongside the
numbers — a status breakdown with no context cannot be compared to anything
later. Worth writing down for each run:

- the commit or state of the code under test
- `VUS`, `SHOW_ID`, `SEAT_ID`, `DISTINCT_USERS`
- the `spread` figure, so you can tell a real collision from a queued one
- the verification query output, not just the HTTP counts
- whether the databases were reset first
- the warm-up: its `GET /api/bookings/{id}` step must send the `X-User-Id` of the user who owns that booking, or it now gets a 404 and warms the ownership check instead of the read path it exists to exercise

Same `VUS`, same reset procedure, same machine, and nothing else running — a
burst test is sensitive to load, and two runs under different conditions are not
a before-and-after.
