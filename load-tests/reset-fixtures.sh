#!/usr/bin/env bash
#
# reset-fixtures.sh - put BookMySeat into one identical, known state.
#
#   ./load-tests/reset-fixtures.sh
#
# Run this before EVERY load-test run. A before/after comparison is only a
# comparison if the starting state was the same both times, and "the same" has
# to mean byte-identical row ids, not merely "roughly reset". Because every
# table is TRUNCATEd (which resets AUTO_INCREMENT) and then re-seeded from
# scratch, the show ids and show_seats ids come out identical on every run.
#
# It is destructive and local-dev only: it empties booking_db and event_db in
# the bookmyseat-mysql container, and deletes every seat:hold:* and idem:* key
# in the bookmyseat-redis container. It never touches auth_db, and it never
# touches any other Redis key.
#
# Redis is part of the known state, not an afterthought. A seat hold is a Redis
# key and nothing else - show_seats has no HELD status, by design - so a hold
# left over from a previous run is invisible to every MySQL check below, yet it
# makes POST /api/bookings/hold return 409 for that seat. A reset that clears
# MySQL but not Redis hands the next run a state that only looks identical.
#
# Idempotency keys are the same gap, one layer over. idem:<op>:<uuid> -> bookingId
# survives the truncate, and it names a booking id that TRUNCATE has just
# deleted and AUTO_INCREMENT is about to hand out again. A replayed key would
# then resolve to a booking from a previous run, or to a live booking that has
# nothing to do with it. Both patterns are cleared, and both are verified at
# zero before READY, for exactly the reason the seat holds are.
#
# Keys are removed by pattern - SCAN then DEL - and never with FLUSHALL or
# FLUSHDB. Redis also holds, or will hold, rate-limit counters. Those belong to
# other concerns, and a load-test fixture reset has no business deleting them.
#
# Safe to run repeatedly, and it makes no assumptions about the current state -
# it will start the MySQL and Redis containers if they are down, and it does
# not care whether the stores are empty, half-full, or full of a previous run's
# bookings and holds.
# There are no interactive steps: it either completes or exits non-zero with a
# message naming the fix.
#
# ---------------------------------------------------------------------------
# HOW event_db IS RE-SEEDED, AND WHY THIS WAY
# ---------------------------------------------------------------------------
# By running event-service's real DemoDataSeeder in a throwaway JVM:
#
#   java -jar event-service.jar --spring.profiles.active=demo \
#                               --spring.main.web-application-type=none
#
# DemoDataSeeder is an ApplicationRunner gated on @Profile("demo"), so there is
# no HTTP endpoint that triggers it - seeding means starting a JVM. But it does
# NOT have to be the JVM under test. web-application-type=none starts no web
# server, runs the ApplicationRunner, and exits by itself in about ten seconds.
#
# The two alternatives were both worse:
#
#   * Restart the running event-service with the demo profile. Unreliable in
#     the way that matters: the script cannot restart an instance started from
#     an IDE, so it would need the developer to do it by hand - an interactive
#     step, which is exactly what this script exists to remove. It would also
#     hand every run a cold JVM, which is a new variable in a measurement whose
#     whole point is that nothing else changed.
#   * Re-create the data over the admin REST API. Needs no restart, but it
#     copies the dataset definition - venue name, row labels, seat count,
#     prices - out of DemoDataSeeder and into this script, where the two would
#     silently drift apart. Then "identical known state" would quietly stop
#     being true and nothing would say so.
#
# The throwaway JVM has neither problem: the seeder stays the single source of
# truth for what the demo dataset is, and the service under test is left alone,
# still running, still warm.
#
# ---------------------------------------------------------------------------
# CONFIGURATION (all overridable from the environment)
# ---------------------------------------------------------------------------
set -Eeuo pipefail

MYSQL_CONTAINER="${MYSQL_CONTAINER:-bookmyseat-mysql}"
DB_USERNAME="${DB_USERNAME:-root}"
DB_PASSWORD="${DB_PASSWORD:-root}"

# The seeder JVM reaches MySQL on the published host port, not through Docker.
DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-13306}"

REDIS_CONTAINER="${REDIS_CONTAINER:-bookmyseat-redis}"

# Optional cross-check through the real read path. Skipped if unreachable.
EVENT_SERVICE_URL="${EVENT_SERVICE_URL:-http://localhost:8082}"

# Which show the printed seat list belongs to. Default: the lowest seeded id,
# which after a truncate-and-reseed is always the same show.
SHOW_ID="${SHOW_ID:-}"

# Bookkeeping table Flyway owns. Emptying it would make the next service start
# try to re-apply V1 against tables that already exist, and fail.
readonly FLYWAY_TABLE='flyway_schema_history'

readonly SCHEMAS=(booking_db event_db)

# The only Redis keys this script owns:
#   seat:hold:<showId>:<showSeatId> -> bookingId   (a live seat hold)
#   idem:hold:<uuid>                -> bookingId   (a used /hold Idempotency-Key)
#   idem:confirm:<uuid>             -> bookingId   (a used /confirm Idempotency-Key)
# Anything else in the keyspace belongs to another concern and is left alone. The
# two idem: spaces are separate on purpose - see IdempotencyService.Operation - and
# the idem:* glob below covers both, so nothing about this script had to change.
readonly HOLD_KEY_PATTERN='seat:hold:*'
readonly IDEM_KEY_PATTERN='idem:*'
readonly OWNED_KEY_PATTERNS=("$HOLD_KEY_PATTERN" "$IDEM_KEY_PATTERN")

# ---------------------------------------------------------------------------

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly REPO_ROOT
readonly COMPOSE_FILE="$REPO_ROOT/docker-compose.infra.yml"
readonly EVENT_JAR="${EVENT_JAR:-$REPO_ROOT/event-service/target/event-service-0.0.1-SNAPSHOT.jar}"
SEED_LOG="$(mktemp -t reset-fixtures-seed.XXXXXX)"
readonly SEED_LOG

die() {
    echo >&2
    echo "FAILED: $*" >&2
    echo >&2
    exit 1
}

step() { echo; echo "== $*"; }

cleanup() { rm -f "$SEED_LOG"; }
trap cleanup EXIT

# Runs one SQL statement (or several, semicolon-separated) against $1.
# --batch --skip-column-names gives tab-separated rows and no decoration, which
# is what the callers below parse. MYSQL_PWD rather than -p so the password is
# not in the container's argv and mysql does not warn on every call.
mysql_q() {
    local database="$1" sql="$2"
    docker exec -e MYSQL_PWD="$DB_PASSWORD" "$MYSQL_CONTAINER" \
        mysql -u"$DB_USERNAME" --batch --skip-column-names --database="$database" -e "$sql"
}

# Runs one redis-cli command in the Redis container. Output is not a TTY, so
# redis-cli prints raw values, one per line, with no "(integer)" decoration.
redis_q() {
    docker exec "$REDIS_CONTAINER" redis-cli "$@"
}

# Prints how many keys match the given pattern. SCAN, never KEYS: KEYS blocks
# the server for the whole keyspace walk. Returns non-zero if Redis cannot be
# reached, so an unreachable Redis is never reported as "0 keys".
count_keys() {
    local pattern="$1" keys
    keys="$(redis_q --scan --pattern "$pattern")" || return 1
    if [ -z "$keys" ]; then
        echo 0
    else
        printf '%s\n' "$keys" | wc -l | tr -d ' '
    fi
}

# Deletes every key matching the given pattern and prints how many DEL removed.
#
# SCAN and DEL both run inside the container, so the key list is piped straight
# into redis-cli instead of costing one docker exec per key. Both key shapes are
# whitespace-free - numeric ids and UUIDs - so xargs word splitting is safe, and
# -n 500 bounds the size of each DEL.
delete_keys() {
    local pattern="$1" deleted
    deleted="$(docker exec "$REDIS_CONTAINER" sh -c \
        "redis-cli --scan --pattern '$pattern' | xargs -r -n 500 redis-cli DEL")" || return 1
    printf '%s\n' "$deleted" | awk '{ n += $1 } END { print n + 0 }'
}

# Starts a compose service if its container is not running, then waits for the
# container's healthcheck. Running is not the same as accepting connections.
ensure_healthy() {
    local container="$1" service="$2" health=''

    if [ "$(docker inspect -f '{{.State.Running}}' "$container" 2>/dev/null || echo false)" != "true" ]; then
        echo "  $container is not running - starting it"
        docker compose -f "$COMPOSE_FILE" up -d "$service" >/dev/null 2>&1 \
            || die "could not start the $service service from $COMPOSE_FILE"
    fi

    printf '  waiting for %s to become healthy' "$container"
    for _ in $(seq 1 60); do
        health="$(docker inspect -f '{{.State.Health.Status}}' "$container" 2>/dev/null || echo unknown)"
        [ "$health" = "healthy" ] && break
        printf '.'
        sleep 2
    done
    echo
    [ "$health" = "healthy" ] || die "$container did not become healthy (last status: $health).
  Look at the logs:
    docker logs $container --tail 50"
}

# ---------------------------------------------------------------------------
# 1. Preflight
# ---------------------------------------------------------------------------

step "Preflight"

command -v docker >/dev/null 2>&1 || die "docker is not on PATH."
docker info >/dev/null 2>&1 || die "the Docker daemon is not reachable. Start Docker Desktop and try again."
command -v java >/dev/null 2>&1 || die "java is not on PATH; the seeder needs it."

[ -f "$EVENT_JAR" ] || die "event-service jar not found at
    $EVENT_JAR
  Build it:
    mvn -pl event-service -am -DskipTests package"

# Start both stores if they are down. No assumption a previous session left them up.
ensure_healthy "$MYSQL_CONTAINER" mysql
ensure_healthy "$REDIS_CONTAINER" redis

echo "  docker daemon      ok"
echo "  mysql container    $MYSQL_CONTAINER healthy"
echo "  redis container    $REDIS_CONTAINER healthy"
echo "  event-service jar  $(basename "$EVENT_JAR")"

# ---------------------------------------------------------------------------
# 2. Truncate MySQL, then clear seat holds in Redis
# ---------------------------------------------------------------------------

# Tables are enumerated from information_schema rather than listed here, so a
# table added by a later migration - an outbox, say - is emptied automatically
# instead of being silently left behind and quietly polluting the next run.
truncate_schema() {
    local schema="$1" tables table sql=''

    tables="$(mysql_q information_schema "
        SELECT table_name
          FROM information_schema.tables
         WHERE table_schema = '$schema'
           AND table_type   = 'BASE TABLE'
           AND table_name  <> '$FLYWAY_TABLE'
         ORDER BY table_name;")" || die "could not list the tables in $schema."

    if [ -z "$tables" ]; then
        die "$schema contains no application tables. Flyway has not run against it.
  Start the owning service once so it applies its migrations, then re-run this script."
    fi

    # FOREIGN_KEY_CHECKS is per session, so the whole batch has to be one call:
    # booking_seats -> bookings is a real FK and TRUNCATE will not cross it.
    sql='SET FOREIGN_KEY_CHECKS = 0;'
    while IFS= read -r table; do
        [ -n "$table" ] || continue
        sql+=" TRUNCATE TABLE \`$table\`;"
        echo "    $schema.$table"
    done <<< "$tables"
    sql+=' SET FOREIGN_KEY_CHECKS = 1;'

    mysql_q "$schema" "$sql" || die "truncating $schema failed."
}

step "Truncating (TRUNCATE also resets AUTO_INCREMENT, so ids repeat every run)"
for schema in "${SCHEMAS[@]}"; do
    truncate_schema "$schema"
done
echo "  $FLYWAY_TABLE left alone in both - Flyway owns it."

step "Clearing seat holds and idempotency keys in Redis (${OWNED_KEY_PATTERNS[*]} only - never FLUSHALL)"

# The counts are only reported here; whether anything survived is checked in the
# verify step, as late as possible, because the real guarantee is what is left,
# not what went.
#
# found and deleted can legitimately differ by a key whose TTL ran out between
# the SCAN and the DEL - Redis expired it first, so DEL counted 0 for it.
for pattern in "${OWNED_KEY_PATTERNS[@]}"; do
    found="$(count_keys "$pattern")" || die "could not scan $REDIS_CONTAINER for $pattern."
    deleted_count="$(delete_keys "$pattern")" || die "deleting $pattern keys in $REDIS_CONTAINER failed."
    printf '  %-14s found %s, deleted %s\n' "$pattern" "$found" "$deleted_count"
done

# ---------------------------------------------------------------------------
# 3. Re-seed event_db
# ---------------------------------------------------------------------------

step "Seeding event_db (throwaway JVM running DemoDataSeeder)"

seed_exit=0
DB_HOST="$DB_HOST" DB_PORT="$DB_PORT" \
DB_USERNAME="$DB_USERNAME" DB_PASSWORD="$DB_PASSWORD" \
java -jar "$EVENT_JAR" \
    --spring.profiles.active=demo \
    --spring.main.web-application-type=none \
    --spring.main.banner-mode=off \
    --logging.level.root=WARN \
    --logging.level.com.bookmyseat.event=INFO \
    > "$SEED_LOG" 2>&1 || seed_exit=$?

if [ "$seed_exit" -ne 0 ]; then
    echo >&2
    echo "--- last 40 lines of the seeder log ---" >&2
    tail -40 "$SEED_LOG" >&2
    echo "---------------------------------------" >&2
    die "the seeder exited $seed_exit. event_db is truncated but NOT seeded; fix the
  error above and re-run this script."
fi

grep -F 'DemoDataSeeder' "$SEED_LOG" | sed 's/^.*DemoDataSeeder *: /  /' || true

# ---------------------------------------------------------------------------
# 4. Read back what was seeded
# ---------------------------------------------------------------------------

step "Seeded catalogue"

mysql_q event_db "
    SELECT CONCAT('  ', LPAD(c, 5, ' '), '  ', t) FROM (
        SELECT 1 o, 'venues'     t, COUNT(*) c FROM venues
        UNION ALL SELECT 2, 'events',     COUNT(*) FROM events
        UNION ALL SELECT 3, 'seats',      COUNT(*) FROM seats
        UNION ALL SELECT 4, 'shows',      COUNT(*) FROM shows
        UNION ALL SELECT 5, 'show_seats', COUNT(*) FROM show_seats
    ) x ORDER BY o;"

echo
echo "  show  starts_at (UTC)               seats  available"
mysql_q event_db "
    SELECT CONCAT('  ', LPAD(s.id, 4, ' '), '  ', RPAD(s.starts_at, 28, ' '),
                  LPAD(COUNT(ss.id), 5, ' '),
                  LPAD(SUM(ss.status = 'AVAILABLE'), 11, ' '))
      FROM shows s JOIN show_seats ss ON ss.show_id = s.id
     GROUP BY s.id, s.starts_at ORDER BY s.id;"

if [ -z "$SHOW_ID" ]; then
    SHOW_ID="$(mysql_q event_db 'SELECT MIN(id) FROM shows;')"
fi
[ -n "$SHOW_ID" ] && [ "$SHOW_ID" != "NULL" ] \
    || die "no shows exist after seeding. The seeder reported success but wrote nothing -
  read the full log and check the demo profile was actually active."

available_ids="$(mysql_q event_db "
    SELECT id FROM show_seats WHERE show_id = $SHOW_ID AND status = 'AVAILABLE' ORDER BY id;")"
[ -n "$available_ids" ] || die "show $SHOW_ID has no AVAILABLE seats immediately after a reseed.
  Something wrote to event_db between the seed and this query."

available_count="$(printf '%s\n' "$available_ids" | wc -l | tr -d ' ')"
total_count="$(mysql_q event_db "SELECT COUNT(*) FROM show_seats WHERE show_id = $SHOW_ID;")"
first_seat="$(printf '%s\n' "$available_ids" | head -1)"

# ---------------------------------------------------------------------------
# 5. Verify - fail loudly rather than hand back a seat that is not free
# ---------------------------------------------------------------------------

step "Verifying seat $first_seat is genuinely AVAILABLE"

# (a) Every seat in the show is free. A freshly seeded show has nothing booked,
#     so anything less means the truncate or the seed did not do what it said.
[ "$available_count" = "$total_count" ] \
    || die "show $SHOW_ID has $available_count AVAILABLE of $total_count seats. A freshly
  seeded show must have all of them free. event_db was not cleanly reset."

# (b) The seat itself, read back individually rather than trusted from the list.
first_status="$(mysql_q event_db "SELECT status FROM show_seats WHERE id = $first_seat;")"
[ "$first_status" = "AVAILABLE" ] \
    || die "show_seats.id=$first_seat is '$first_status', not AVAILABLE. Do not run the load
  test against it - every request would 409 and the run would measure nothing."
echo "  event_db.show_seats.id=$first_seat  status=AVAILABLE"

# (c) booking_db must be empty. It is a separate schema with no FK to event_db,
#     so a leftover booking_seats row claiming this seat would not show up in
#     (b) at all - and it is precisely what the post-run verification query
#     counts. This is the shell reaching into two schemas, which a *service* may
#     never do (CLAUDE.md); a fixture script is not a service.
leftovers="$(mysql_q booking_db "
    SELECT CONCAT((SELECT COUNT(*) FROM bookings), ' ', (SELECT COUNT(*) FROM booking_seats));")"
[ "$leftovers" = "0 0" ] \
    || die "booking_db is not empty after truncation (bookings booking_seats = $leftovers).
  Something inserted between the truncate and now - is a load test already running?"
echo "  booking_db               bookings=0  booking_seats=0"

# (d) Redis must hold neither seat holds nor idempotency keys - checked, not
#     assumed from the delete. Both pass (a)-(c) untouched: the database has no
#     HELD status, and a used idem: key leaves no trace in MySQL once bookings
#     has been truncated. A leftover hold makes /hold return 409 for that seat;
#     a leftover idem: key makes /hold return a booking from a previous run
#     instead of creating one. Either appearing between the delete and now means
#     something is still taking traffic, so the script stops.
hold_keys=''
idem_keys=''
for pattern in "${OWNED_KEY_PATTERNS[@]}"; do
    remaining="$(count_keys "$pattern")" \
        || die "could not scan $REDIS_CONTAINER to verify $pattern keys are gone."
    if [ "$remaining" != "0" ]; then
        die "$remaining $pattern key(s) remain in $REDIS_CONTAINER after the delete:
$(redis_q --scan --pattern "$pattern" | head -20 | sed 's/^/    /')
  Something is still writing them - is a load test or other traffic hitting
  booking-service? Stop it and re-run this script."
    fi
    # Reported below from the count that was actually read back, not from a literal:
    # the READY block should state what this scan found, the same as every other
    # check here does.
    case "$pattern" in
        "$HOLD_KEY_PATTERN") hold_keys="$remaining" ;;
        "$IDEM_KEY_PATTERN") idem_keys="$remaining" ;;
    esac
done
other_keys="$(redis_q DBSIZE)" || die "could not read DBSIZE from $REDIS_CONTAINER."
echo "  redis                    seat:hold:*=0  idem:*=0  (other keys, left alone: $other_keys)"

# (e) Optional: ask event-service, which is the path booking-service actually
#     reads through. Skipped when it is not up, because resetting before the
#     services are started is legitimate. But if it IS up and disagrees with the
#     database, that is a real problem and the script stops.
if command -v curl >/dev/null 2>&1 \
   && seat_map="$(curl -fsS --max-time 5 "$EVENT_SERVICE_URL/api/shows/$SHOW_ID/seats" 2>/dev/null)"; then
    if printf '%s' "$seat_map" | grep -Eq "\"id\":$first_seat,[^}]*\"status\":\"AVAILABLE\""; then
        echo "  event-service            agrees: seat $first_seat is AVAILABLE"
    else
        die "event-service does not report seat $first_seat as AVAILABLE, but event_db says it is.
  The running event-service is looking at a different database than this script.
  Check DB_HOST/DB_PORT and which profile event-service was started with."
    fi
else
    echo "  event-service            not reachable at $EVENT_SERVICE_URL - skipped"
    echo "                           (fine if the services are not started yet)"
fi

# ---------------------------------------------------------------------------
# 6. Report
# ---------------------------------------------------------------------------

echo
echo "------------------------------------------------------------------"
echo "  READY"
echo "------------------------------------------------------------------"
echo "  SHOW_ID   $SHOW_ID"
echo "  SEAT_ID   $first_seat        <- first AVAILABLE seat, verified above"
echo "  HOLDS     $hold_keys        <- seat:hold:* keys in Redis, verified above"
echo "  IDEM      $idem_keys        <- idem:* keys in Redis, verified above"
echo
echo "  AVAILABLE seat ids for show $SHOW_ID ($available_count of $total_count):"
# tr strips the trailing newline, so fold's last line arrives unterminated -
# the first echo ends that line, the second is the blank one.
printf '%s\n' "$available_ids" | tr '\n' ' ' | fold -s -w 64 | sed 's/^/    /'
echo
echo
echo "  Run the load test with:"
echo
echo "    docker run --rm -i -v \"\$PWD/load-tests:/scripts\" \\"
echo "      -e BASE_URL=http://host.docker.internal:8083 \\"
echo "      -e SHOW_ID=$SHOW_ID -e SEAT_ID=$first_seat \\"
echo "      grafana/k6 run /scripts/single-seat-contention.js"
echo
echo "  (Git Bash on Windows rewrites /scripts into a Windows path. Prefix that"
echo "   command with MSYS_NO_PATHCONV=1, or run it from PowerShell.)"
echo "------------------------------------------------------------------"
