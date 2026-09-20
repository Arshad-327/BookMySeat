#!/usr/bin/env bash
#
# orphan-regression.sh - the reproduction of review finding #1, turned into a
# regression test.
#
#   ./scripts/orphan-regression.sh
#
# Finding #1: a SLOW event-service - not a crashed one - commits
# show_seats.status = 'BOOKED' after booking-service's 5s read timeout has
# already fired and rolled its confirm back. The seat is sold and no booking
# claims it. Nothing in the system could tell that from a normal sale, and
# nothing ever freed it.
#
# The fix does not prevent the timeout. It makes the timeout RECOVERABLE, three
# ways, and this script is one case per way:
#
#   CASE 1  the caller retries         -> the confirm succeeds, idempotently
#   CASE 2  the caller never returns   -> the sweeper releases the seat
#   CASE 3  the caller cancels         -> cancel releases the seat
#
# Each case first reproduces the orphan and asserts it looks EXACTLY like the
# bug - BOOKED seat, PENDING booking, no outbox row. That half is not something
# to be fixed. If it ever stops reproducing, something else changed and the rest
# of the script is proving nothing.
#
# ---------------------------------------------------------------------------
# WHY THIS IS A SEPARATE SCRIPT AND NOT A SECTION OF e2e-smoke.sh
# ---------------------------------------------------------------------------
# e2e-smoke.sh runs against the normal stack. This one cannot: it needs
# event-service ARMED with a fault delay and booking-service on a SHORT seat-hold
# TTL, neither of which is a normal running configuration. Folding it in would
# mean either the smoke test silently requires non-default startup flags - so a
# developer following its own instructions gets a red run that means nothing - or
# it restarts services it does not own. Both are worse than a second file.
#
# It is also a different kind of test. The smoke test walks the happy path and
# checks the doors are shut. This one deliberately breaks a service, reads both
# databases behind the platform's back, and waits on a scheduler.
#
# ---------------------------------------------------------------------------
# WALL-CLOCK BUDGET: about 4 minutes, dominated by two waits it cannot shorten
# ---------------------------------------------------------------------------
#   3 x reset-fixtures.sh (throwaway seeder JVM)       ~45s
#   case 1  timeout, commit-after-timeout, retry       ~20s
#   case 2  as above, then a hold lapse and a sweep    ~110s   <- the long one
#   case 3  as above, then cancel and resell           ~25s
#                                                      -------
#                                                      ~200s
#
# Case 2's wait is not padding and cannot be tuned away:
#
#   * The hold has to actually lapse. SEAT_HOLD_TTL below must match what
#     booking-service was started with, and the script verifies that it does
#     rather than trusting it.
#   * The sweeper's interval is HARDCODED. ExpiredBookingSweeper is annotated
#     @Scheduled(fixedDelayString = "PT60S", initialDelayString = "PT60S") with
#     literals, not ${...} placeholders, so there is no property to override and
#     no way to make it run sooner without changing code. Adding a property for
#     this script's convenience would be changing production code to suit a test,
#     so the script waits the cycle instead. Worst case that is a full 60s after
#     the hold lapses, which is what SWEEP_TIMEOUT_SECONDS allows for.
#
#   app.seat-hold.ttl, by contrast, IS overridable at startup - it binds to
#   SeatHoldProperties as a Duration - which is why the hold lapse costs seconds
#   and not ten minutes.
#
# ---------------------------------------------------------------------------
# REQUIREMENTS - AND THE TWO NON-DEFAULT STARTUP FLAGS
# ---------------------------------------------------------------------------
#   bash, curl, jq
#   docker, with the infra from docker-compose.infra.yml up
#   all five services running: gateway 8080, auth 8081, event 8082,
#   booking 8083, notification 8085
#   event-service's jar built, for reset-fixtures.sh's throwaway seeder JVM
#
# event-service MUST be started armed, and booking-service with a short TTL:
#
#   java -jar event-service/target/event-service-0.0.1-SNAPSHOT.jar \
#        --app.fault.book-seats-delay=8s
#
#   java -jar booking-service/target/booking-service-0.0.1-SNAPSHOT.jar \
#        --app.seat-hold.ttl=30s
#
# 8s against booking-service's unchanged 5s read timeout is what produces the
# timeout; the extra 3s is margin, not magic. An armed event-service logs a WARN
# on every seat write saying it is deliberately broken - see
# SeatBookingFaultProperties.
#
# THE SCRIPT DOES NOT REQUIRE THE ARMING TO SUCCEED. If event-service is not
# armed, the confirms return 200 straight away, the orphan assertions are skipped
# with a note, and every case still asserts its FINAL state. That is deliberate:
# it is how you check that these assertions are not quietly depending on the
# fault being armed. Run it both ways.
#
# ---------------------------------------------------------------------------
# WHY THIS ONE READS THE DATABASES DIRECTLY
# ---------------------------------------------------------------------------
# Every platform request goes through the gateway on 8080, as in e2e-smoke.sh.
# But the orphan is BY DEFINITION a disagreement between two databases that no
# API exposes: booking_db says PENDING while event_db says BOOKED. There is no
# endpoint that can show that, and there should not be. So the assertions read
# booking_db and event_db directly, through docker exec, the way
# reset-fixtures.sh does. A script may reach into two schemas; a service may not
# (CLAUDE.md).
#
# ---------------------------------------------------------------------------
# REQUEST BUDGET
# ---------------------------------------------------------------------------
# RateLimitFilter is a token bucket per client IP: capacity 40, refill 2/second.
# This script makes about 30 gateway requests over roughly 200 seconds, and the two
# long waits refill the bucket many times over, so the limiter is never close to
# being the constraint here. It is still counted and printed at the end, for the
# same reason the smoke test counts its own.
# ---------------------------------------------------------------------------
set -Eeuo pipefail

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8080}"
MAILHOG_URL="${MAILHOG_URL:-http://localhost:8025}"

MYSQL_CONTAINER="${MYSQL_CONTAINER:-bookmyseat-mysql}"
DB_USERNAME="${DB_USERNAME:-root}"
DB_PASSWORD="${DB_PASSWORD:-root}"

# Must match booking-service's --app.seat-hold.ttl. Verified at preflight from a
# real hold's own expiresAt, not trusted: a mismatch here would show up as case 2
# timing out after two minutes with no explanation.
SEAT_HOLD_TTL="${SEAT_HOLD_TTL:-30}"

# Longer than the read timeout, so the request has definitely come back. The
# confirm itself gives up at 5s.
CURL_TIMEOUT="${CURL_TIMEOUT:-20}"

# How long to wait for event-service to finish committing AFTER booking-service
# gave up on it. The injected delay is 8s and the timeout fires at 5s, so the
# remaining ~3s is what this covers, with margin.
ORPHAN_SETTLE_SECONDS="${ORPHAN_SETTLE_SECONDS:-8}"

# The sweeper runs every 60s and nothing can make it run sooner. 90 is one full
# cycle plus margin; anything less is a flake waiting to happen.
SWEEP_TIMEOUT_SECONDS="${SWEEP_TIMEOUT_SECONDS:-90}"

MAIL_TIMEOUT_SECONDS="${MAIL_TIMEOUT_SECONDS:-30}"

PASSWORD="${REGRESSION_PASSWORD:-OrphanTest123!}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly REPO_ROOT
readonly FIXTURES="$REPO_ROOT/load-tests/reset-fixtures.sh"

RUN_ID="$(date +%s)-$$"
readonly RUN_ID

WORK_DIR="$(mktemp -d -t orphan-regression.XXXXXX)"
readonly WORK_DIR
readonly BODY_FILE="$WORK_DIR/body"
readonly ERR_FILE="$WORK_DIR/err"
readonly FIXTURES_LOG="$WORK_DIR/reset-fixtures.log"
trap 'rm -rf "$WORK_DIR"' EXIT

REQUESTS=0
CASE=""
STEP_NAME=""
HTTP_STATUS=""
HTTP_BODY=""
ARMED="unknown"
TOKEN=""
BOOKING=""

# ---------------------------------------------------------------------------
# OUTPUT
# ---------------------------------------------------------------------------

banner() {
    echo
    echo "=================================================================="
    echo "  $*"
    echo "=================================================================="
}

step() { STEP_NAME="$1"; }

pass() { printf 'PASS  %-7s %-46s %s\n' "$CASE" "$STEP_NAME" "$1"; }

note() { printf '      %-7s %-46s %s\n' "$CASE" "" "$1"; }

fail() {
    printf '\n' >&2
    printf 'FAIL  %s  %s\n' "$CASE" "$STEP_NAME" >&2
    printf '        expected: %s\n' "$1" >&2
    if [ -n "$HTTP_STATUS" ]; then
        printf '        actual:   HTTP %s\n' "$HTTP_STATUS" >&2
        printf '        body:     %s\n' "$(printf '%s' "$HTTP_BODY" | head -c 800)" >&2
    fi
    printf '\n' >&2
    printf 'Stopped in %s after %s gateway requests.\n' "$CASE" "$REQUESTS" >&2
    exit 1
}

die() { HTTP_STATUS=""; fail "$1"; }

# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------

req() {
    local method="$1" path="$2"
    shift 2
    REQUESTS=$((REQUESTS + 1))
    HTTP_STATUS="$(curl -sS -o "$BODY_FILE" -w '%{http_code}' \
        -X "$method" --max-time "$CURL_TIMEOUT" \
        "$GATEWAY_URL$path" "$@" 2>"$ERR_FILE" || true)"
    HTTP_BODY="$(cat "$BODY_FILE" 2>/dev/null || true)"
    if [ -z "$HTTP_STATUS" ] || [ "$HTTP_STATUS" = "000" ]; then
        HTTP_STATUS="000 (no response)"
        HTTP_BODY="$(cat "$ERR_FILE" 2>/dev/null || true)"
    fi
}

expect_status() { [ "$HTTP_STATUS" = "$1" ] || fail "HTTP $1 - $2"; }

body_field() {
    local value
    value="$(printf '%s' "$HTTP_BODY" | jq -r "$1" 2>/dev/null || true)"
    if [ -z "$value" ] || [ "$value" = "null" ]; then
        fail "a response with $1 in it"
    fi
    printf '%s' "$value"
}

json() { printf '%s' "$HTTP_BODY" | jq -r "$1" 2>/dev/null || true; }

uuid() {
    if [ -r /proc/sys/kernel/random/uuid ]; then
        cat /proc/sys/kernel/random/uuid
    else
        powershell -NoProfile -Command "[guid]::NewGuid().ToString()" | tr -d '\r\n'
    fi
}

# ---------------------------------------------------------------------------
# DATABASE
# ---------------------------------------------------------------------------
# One value out of one schema. --batch --skip-column-names so the result is the
# bare value; MYSQL_PWD so the password stays out of the container's argv.

sql() {
    local schema="$1" query="$2"
    docker exec -e MYSQL_PWD="$DB_PASSWORD" "$MYSQL_CONTAINER" \
        mysql -u"$DB_USERNAME" --batch --skip-column-names --database="$schema" -e "$query" \
        2>/dev/null | tr -d '\r'
}

# assert_db SCHEMA QUERY EXPECTED WHAT
#
# NULL comes back from --batch as the four characters NULL, which is why the
# expected values below are written that way rather than as an empty string.
assert_db() {
    local schema="$1" query="$2" expected="$3" what="$4" actual
    actual="$(sql "$schema" "$query")"
    if [ "$actual" != "$expected" ]; then
        HTTP_STATUS=""
        printf '\n' >&2
        printf 'FAIL  %s  %s\n' "$CASE" "$STEP_NAME" >&2
        printf '        expected: %s = %s\n' "$what" "$expected" >&2
        printf '        actual:   %s = %s\n' "$what" "${actual:-<empty>}" >&2
        printf '        query:    %s\n' "$(printf '%s' "$query" | tr -s ' \n' ' ')" >&2
        printf '\n' >&2
        exit 1
    fi
}

# ---------------------------------------------------------------------------
# THE ORPHAN, ASSERTED AS A WHOLE
# ---------------------------------------------------------------------------
# This is the bug, exactly as review finding #1 describes it, and it must keep
# reproducing. The fix is downstream of this state, not instead of it.

assert_orphan_state() {
    local booking="$1" seat="$2"

    # event_db believes the seat is sold, and now says who to.
    assert_db event_db "SELECT status FROM show_seats WHERE id = $seat;" \
        "BOOKED" "show_seats.status"
    assert_db event_db "SELECT booked_by_booking_id FROM show_seats WHERE id = $seat;" \
        "$booking" "show_seats.booked_by_booking_id"
    # Written exactly once. A second write here would mean the retry path is not
    # the one being taken.
    assert_db event_db "SELECT version FROM show_seats WHERE id = $seat;" \
        "1" "show_seats.version"

    # booking_db believes nothing happened.
    assert_db booking_db "SELECT status FROM bookings WHERE id = $booking;" \
        "PENDING" "bookings.status"
    assert_db booking_db \
        "SELECT IFNULL(sold_show_seat_id, 'NULL') FROM booking_seats WHERE booking_id = $booking AND show_seat_id = $seat;" \
        "NULL" "booking_seats.sold_show_seat_id"
    # And nothing was published, because the transaction that would have written
    # the outbox row rolled back with everything else.
    assert_db booking_db "SELECT COUNT(*) FROM outbox;" \
        "0" "outbox rows"
}

# ---------------------------------------------------------------------------
# FIXTURES
# ---------------------------------------------------------------------------
# Between cases, not just at the start. Each case books the same seat, and a
# case that inherited the previous one's rows could pass on residue: case 3's
# "the seat is sellable again" would be trivially true if case 2 had already
# freed it. reset-fixtures.sh truncates booking_db and event_db, clears every
# seat:hold:* and idem:* key in Redis, and re-seeds event_db from
# DemoDataSeeder in a throwaway JVM - which resets AUTO_INCREMENT, so the show
# and seat ids are identical in all three cases.
#
# auth_db is deliberately NOT reset by it, so every case registers a fresh user
# with a RUN_ID-and-case-unique email rather than colliding with the last one.

SHOW_ID=""
SEAT_A=""
SEAT_B=""

reset_fixtures() {
    step "reset fixtures"
    [ -x "$FIXTURES" ] || die "$FIXTURES to exist and be executable"
    "$FIXTURES" > "$FIXTURES_LOG" 2>&1 \
        || { tail -25 "$FIXTURES_LOG" >&2; die "reset-fixtures.sh to succeed"; }

    SHOW_ID="$(sql event_db 'SELECT MIN(id) FROM shows;')"
    [ -n "$SHOW_ID" ] && [ "$SHOW_ID" != "NULL" ] || die "a seeded show"

    # Two seats, so the orphan covers a multi-seat booking rather than the one
    # case where "all of them" and "the first one" are the same thing.
    SEAT_A="$(sql event_db "SELECT id FROM show_seats WHERE show_id = $SHOW_ID AND status = 'AVAILABLE' ORDER BY id LIMIT 1;")"
    SEAT_B="$(sql event_db "SELECT id FROM show_seats WHERE show_id = $SHOW_ID AND status = 'AVAILABLE' ORDER BY id LIMIT 1 OFFSET 1;")"
    [ -n "$SEAT_A" ] && [ -n "$SEAT_B" ] || die "two AVAILABLE seats in show $SHOW_ID"

    assert_db booking_db "SELECT COUNT(*) FROM bookings;" "0" "bookings after reset"
    assert_db booking_db "SELECT COUNT(*) FROM outbox;" "0" "outbox after reset"
    pass "show $SHOW_ID, seats $SEAT_A and $SEAT_B, both databases empty"
}

# ---------------------------------------------------------------------------
# These two set globals instead of printing their result, which looks clumsier
# than `TOKEN="$(register_user c1)"` and is the only version that works. A
# command substitution runs in a SUBSHELL: the helper's req would set HTTP_BODY
# there and the caller would still be looking at the previous response. The
# preflight TTL check reads the hold's own body right after calling hold_seats,
# so that difference is the whole check.
# ---------------------------------------------------------------------------

# register_user SUFFIX -> sets TOKEN
register_user() {
    # Two statements, not one `local a=.. b=$a`: under `set -u` this bash reports the
    # first name as unbound while evaluating the second on the same line.
    local suffix="$1"
    local email="orphan-$RUN_ID-$suffix@example.com"
    req POST /api/auth/register \
        -H 'Content-Type: application/json' \
        -d "{\"email\":\"$email\",\"password\":\"$PASSWORD\",\"name\":\"Orphan $suffix\"}"
    [ "$HTTP_STATUS" = "201" ] || [ "$HTTP_STATUS" = "200" ] \
        || fail "HTTP 201 registering $email"

    # Register returns the user, not a token - so two requests per user, as in
    # e2e-smoke.sh. The second one earns its place: it is the only thing here that
    # proves the credentials just created actually work.
    req POST /api/auth/login \
        -H 'Content-Type: application/json' \
        -d "{\"email\":\"$email\",\"password\":\"$PASSWORD\"}"
    expect_status 200 "the credentials just registered are accepted"
    TOKEN="$(body_field '.accessToken')"
}

# hold_seats TOKEN SEAT... -> sets BOOKING, and leaves the hold's body in HTTP_BODY
hold_seats() {
    local token="$1"
    shift
    local ids
    ids="$(printf '%s,' "$@")"
    ids="${ids%,}"
    req POST /api/bookings/hold \
        -H "Authorization: Bearer $token" \
        -H "Idempotency-Key: $(uuid)" \
        -H 'Content-Type: application/json' \
        -d "{\"showId\":$SHOW_ID,\"seatIds\":[$ids]}"
    expect_status 201 "the hold is created"
    BOOKING="$(body_field '.id')"
}

# The confirm that is expected to time out. Returns 0 if it produced the orphan
# (503), 1 if event-service was not armed and it simply worked (200).
#
# Anything else is a failure: this must be one of those two outcomes, never a
# 409 or a 500, or the case that follows is interpreting a different bug.
confirm_expecting_timeout() {
    local token="$1" booking="$2"
    req POST "/api/bookings/$booking/confirm" -H "Authorization: Bearer $token"
    case "$HTTP_STATUS" in
        503)
            [ "$(json '.message')" = "The booking could not be confirmed, please retry" ] \
                || fail "the confirm-specific 503 message, not the generic one"
            ARMED="yes"
            return 0
            ;;
        200)
            ARMED="no"
            return 1
            ;;
        *)
            fail "HTTP 503 (armed) or HTTP 200 (not armed) from the confirm"
            ;;
    esac
}

# Reproduces the orphan for one booking, when event-service is armed. When it is
# not, there is no timeout and therefore no orphan - the confirm is SKIPPED
# entirely rather than attempted.
#
# This is the shape an earlier draft got wrong: disarmed, the confirm simply
# SUCCEEDS, and a case that went on to cancel or expire a CONFIRMED booking was
# asserting on a scenario that had not happened. A disarmed run must exercise the
# ordinary path - a hold that is never confirmed - not a broken version of the
# armed one.
reproduce_orphan_or_skip() {
    local token="$1"
    local booking="$2"
    local seat="$3"
    if [ "$ARMED" != "yes" ]; then
        note "event-service is NOT armed: no timeout, so no orphan to reproduce."
        note "The hold is simply never confirmed, and the recovery path below"
        note "still has to free the seat and leave it sellable."
        return
    fi
    step "confirm times out"
    confirm_expecting_timeout "$token" "$booking" \
        || fail "HTTP 503 from an armed event-service"
    sleep "$ORPHAN_SETTLE_SECONDS"
    step "the orphan is exactly as finding #1 describes"
    assert_orphan_state "$booking" "$seat"
    pass "seat $seat BOOKED to booking $booking, booking PENDING, no outbox row"
}

# Confirms a booking for real, retrying once past the injected delay when armed.
# A recovered seat has to be sellable the ordinary way, which against an armed
# event-service means the ordinary way INCLUDING its timeout and retry.
confirm_for_real() {
    local token="$1"
    local booking="$2"
    req POST "/api/bookings/$booking/confirm" -H "Authorization: Bearer $token"
    if [ "$HTTP_STATUS" = "503" ]; then
        sleep "$ORPHAN_SETTLE_SECONDS"
        req POST "/api/bookings/$booking/confirm" -H "Authorization: Bearer $token"
    fi
}

wait_for_email() {
    local booking="$1" waited=0
    while [ "$waited" -lt "$MAIL_TIMEOUT_SECONDS" ]; do
        if curl -fsS --max-time 10 "$MAILHOG_URL/api/v2/messages?limit=50" 2>/dev/null \
             | jq -e --arg b "$booking" '.items | length > 0' >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
        waited=$((waited + 1))
    done
    return 1
}

# ---------------------------------------------------------------------------
# PREFLIGHT
# ---------------------------------------------------------------------------

banner "Preflight"
CASE="setup"

for tool in curl jq docker; do
    command -v "$tool" >/dev/null 2>&1 || die "$tool on PATH"
done
docker info >/dev/null 2>&1 || die "the Docker daemon to be reachable"

step "gateway is up"
req GET /api/events
expect_status 200 "the gateway is serving the public catalogue"
pass "$GATEWAY_URL"

reset_fixtures

# The seat-hold TTL, read off a real hold rather than assumed. A booking-service
# still on the 600s default would make case 2 sit for ninety seconds and then
# fail on a state that was never going to change, which is the least debuggable
# way for this to go wrong.
step "seat-hold TTL is short"
CASE="setup"
register_user "preflight"; PREFLIGHT_TOKEN="$TOKEN"
hold_seats "$PREFLIGHT_TOKEN" "$SEAT_A"; PREFLIGHT_BOOKING="$BOOKING"
HOLD_WINDOW="$(printf '%s' "$HTTP_BODY" | jq -r '
    (.expiresAt | sub("\\.[0-9]+Z$"; "Z") | fromdateiso8601)
  - (.createdAt | sub("\\.[0-9]+Z$"; "Z") | fromdateiso8601)')"
if [ "$HOLD_WINDOW" -gt $((SEAT_HOLD_TTL + 5)) ] 2>/dev/null; then
    die "booking-service started with --app.seat-hold.ttl=${SEAT_HOLD_TTL}s.
  A hold it just issued lasts ${HOLD_WINDOW}s, so it is running on a longer TTL
  (the default is 600s). Case 2 waits for a hold to lapse and would time out."
fi
pass "a hold lasts ${HOLD_WINDOW}s - app.seat-hold.ttl override is in effect"

# Undo the preflight hold so case 1 starts from a clean slate.
req DELETE "/api/bookings/$PREFLIGHT_BOOKING" -H "Authorization: Bearer $PREFLIGHT_TOKEN"
expect_status 200 "the preflight hold is cancelled"

# ---------------------------------------------------------------------------
# CASE 1 - the timeout is now recoverable
# ---------------------------------------------------------------------------

banner "CASE 1 - confirm times out, the caller retries, the retry succeeds"
CASE="case 1"
reset_fixtures

register_user "c1"; TOKEN1="$TOKEN"

step "hold two seats"
hold_seats "$TOKEN1" "$SEAT_A" "$SEAT_B"; BOOKING1="$BOOKING"
pass "booking $BOOKING1 holds seats $SEAT_A and $SEAT_B"

step "confirm times out"
if confirm_expecting_timeout "$TOKEN1" "$BOOKING1"; then
    pass "503, and the message is confirm-specific, not \"nothing was written\""

    # event-service is still committing at this point: booking-service gave up at
    # 5s, the injected delay runs to 8s. Reading the orphan before it commits
    # would read the state before the bug, not the bug.
    step "wait for event-service to commit after the caller gave up"
    sleep "$ORPHAN_SETTLE_SECONDS"
    pass "waited ${ORPHAN_SETTLE_SECONDS}s"

    step "the orphan is exactly as finding #1 describes"
    assert_orphan_state "$BOOKING1" "$SEAT_A"
    assert_orphan_state "$BOOKING1" "$SEAT_B"
    pass "seats BOOKED to booking $BOOKING1, booking PENDING, unsold, no outbox row"
else
    note "event-service is NOT armed - the confirm returned 200 with no timeout."
    note "Skipping the orphan assertions; the final assertions below still apply."
fi

step "retry the confirm"
req POST "/api/bookings/$BOOKING1/confirm" -H "Authorization: Bearer $TOKEN1"
if [ "$ARMED" = "yes" ]; then
    expect_status 200 "the retry succeeds where the first attempt timed out"
    [ "$(json '.status')" = "CONFIRMED" ] || fail "status CONFIRMED in the retry's response"
    pass "200 CONFIRMED - the seats it already owned were accepted, not refused"
else
    # Already CONFIRMED by the first call. A second confirm is correctly a 409.
    [ "$HTTP_STATUS" = "409" ] || fail "HTTP 409 confirming an already-CONFIRMED booking"
    pass "409, already confirmed by the first attempt (not armed)"
fi

step "the two databases now agree"
assert_db booking_db "SELECT status FROM bookings WHERE id = $BOOKING1;" \
    "CONFIRMED" "bookings.status"
for seat in "$SEAT_A" "$SEAT_B"; do
    assert_db booking_db \
        "SELECT IFNULL(sold_show_seat_id, 'NULL') FROM booking_seats WHERE booking_id = $BOOKING1 AND show_seat_id = $seat;" \
        "$seat" "booking_seats.sold_show_seat_id for seat $seat"
    assert_db event_db "SELECT status FROM show_seats WHERE id = $seat;" \
        "BOOKED" "show_seats.status for seat $seat"
    assert_db event_db "SELECT booked_by_booking_id FROM show_seats WHERE id = $seat;" \
        "$BOOKING1" "show_seats.booked_by_booking_id for seat $seat"
done
assert_db booking_db "SELECT COUNT(*) FROM outbox;" "1" "outbox rows"
pass "booking CONFIRMED and sold, seats BOOKED and owned, exactly one outbox row"

# ---------------------------------------------------------------------------
# THE ASSERTION THAT SEPARATES A FIX FROM AN ACCIDENT
# ---------------------------------------------------------------------------
# version is still 1. The first attempt wrote the row; the retry recognised its
# own claim and wrote NOTHING.
#
# A retry that re-wrote the row with the same values would look identical in
# every assertion above and would read 2 here. It would also be wrong: it would
# move the version, invalidate any concurrent reader's copy, and make a replay
# capable of losing an optimistic-lock race it has no business being in.
#
# If this ever reads 2, the idempotent path is not being taken and everything
# else passing is a coincidence.
step "the retry wrote nothing"
for seat in "$SEAT_A" "$SEAT_B"; do
    assert_db event_db "SELECT version FROM show_seats WHERE id = $seat;" \
        "1" "show_seats.version for seat $seat"
done
pass "show_seats.version is still 1 - the retry took the no-write path"

step "the confirmation email arrives"
wait_for_email "$BOOKING1" || die "a confirmation email in MailHog within ${MAIL_TIMEOUT_SECONDS}s"
pass "MailHog has the confirmation"

# ---------------------------------------------------------------------------
# CASE 2 - expiry with no retry
# ---------------------------------------------------------------------------

banner "CASE 2 - confirm times out, nobody retries, the sweeper recovers the seat"
CASE="case 2"
reset_fixtures

register_user "c2"; TOKEN2="$TOKEN"

step "hold the seat"
hold_seats "$TOKEN2" "$SEAT_A"; BOOKING2="$BOOKING"
pass "booking $BOOKING2 holds seat $SEAT_A"

reproduce_orphan_or_skip "$TOKEN2" "$BOOKING2" "$SEAT_A"

# Nothing retries. The hold lapses, the sweeper comes round, and the booking and
# the seat both have to move on their own.
#
# Polled on the BOOKING, not the seat: PENDING -> EXPIRED is the sweeper's
# observable action in BOTH modes, whereas the seat only changes when there was an
# orphan to clean up. Waiting on the seat would return instantly on a disarmed run
# and report that the sweeper had run when it had not.
step "wait for the hold to lapse and the sweeper to run"
WAITED=0
while [ "$WAITED" -lt "$SWEEP_TIMEOUT_SECONDS" ]; do
    if [ "$(sql booking_db "SELECT status FROM bookings WHERE id = $BOOKING2;")" = "EXPIRED" ]; then
        break
    fi
    sleep 5
    WAITED=$((WAITED + 5))
done
[ "$WAITED" -lt "$SWEEP_TIMEOUT_SECONDS" ] \
    || die "the sweeper to expire booking $BOOKING2 within ${SWEEP_TIMEOUT_SECONDS}s.
  The sweeper runs every 60s (hardcoded) and the hold lapses after ${SEAT_HOLD_TTL}s.
  Check booking-service's log for 'expiry sweep' and for app.scheduling.enabled."
pass "expired after ~${WAITED}s"

step "the seat is genuinely free, and the booking says so"
assert_db event_db "SELECT status FROM show_seats WHERE id = $SEAT_A;" \
    "AVAILABLE" "show_seats.status"
# Cleared, not merely ignored. A seat left AVAILABLE while still naming an owner
# is the mirror of the orphan, and the next section is what would catch it.
assert_db event_db "SELECT IFNULL(booked_by_booking_id, 'NULL') FROM show_seats WHERE id = $SEAT_A;" \
    "NULL" "show_seats.booked_by_booking_id"
assert_db booking_db "SELECT status FROM bookings WHERE id = $BOOKING2;" \
    "EXPIRED" "bookings.status"
pass "AVAILABLE, owner NULL, booking EXPIRED"

# ---------------------------------------------------------------------------
# "AVAILABLE" IS NOT THE REQUIREMENT. "SOMEBODY CAN BUY IT" IS.
# ---------------------------------------------------------------------------
# A release that cleared the status but left booked_by_booking_id set would pass
# every assertion above. The seat would read AVAILABLE, and then markBooked would
# refuse it forever for every booking but the dead one - un-sellable by a route
# nothing else in this script looks at. The only way to catch that is to sell it.
step "a different user can actually buy the seat"
register_user "c2b"; TOKEN2B="$TOKEN"
hold_seats "$TOKEN2B" "$SEAT_A"; BOOKING2B="$BOOKING"
# Armed, this confirm times out exactly as the first one did and its own retry
# completes the sale: a recovered seat behaves like any other seat, including in
# how it fails.
confirm_for_real "$TOKEN2B" "$BOOKING2B"
expect_status 200 "the recovered seat can be sold to somebody else"
[ "$(json '.status')" = "CONFIRMED" ] || fail "status CONFIRMED for the new booking"
assert_db event_db "SELECT booked_by_booking_id FROM show_seats WHERE id = $SEAT_A;" \
    "$BOOKING2B" "show_seats.booked_by_booking_id"
pass "booking $BOOKING2B owns seat $SEAT_A - the seat was recovered, not just relabelled"

# ---------------------------------------------------------------------------
# CASE 3 - cancel instead of expiry
# ---------------------------------------------------------------------------
# This is the case that fails without cancel's own release. Before that change,
# cancel flipped the booking to CANCELLED and left the seat BOOKED with nobody
# coming back for it - the sweeper selects PENDING only.

banner "CASE 3 - confirm times out, the user cancels, the seat comes back"
CASE="case 3"
reset_fixtures

register_user "c3"; TOKEN3="$TOKEN"

step "hold the seat"
hold_seats "$TOKEN3" "$SEAT_A"; BOOKING3="$BOOKING"
pass "booking $BOOKING3 holds seat $SEAT_A"

reproduce_orphan_or_skip "$TOKEN3" "$BOOKING3" "$SEAT_A"

step "cancel the booking"
req DELETE "/api/bookings/$BOOKING3" -H "Authorization: Bearer $TOKEN3"
expect_status 200 "the cancel succeeds"
[ "$(json '.status')" = "CANCELLED" ] || fail "status CANCELLED in the response"
pass "booking $BOOKING3 CANCELLED"

step "cancel released the seat, not just the booking"
# Immediately - no sweeper involved. Cancel's release runs inside its own
# transaction, so by the time the 200 was returned the seat was already back.
assert_db event_db "SELECT status FROM show_seats WHERE id = $SEAT_A;" \
    "AVAILABLE" "show_seats.status"
assert_db event_db "SELECT IFNULL(booked_by_booking_id, 'NULL') FROM show_seats WHERE id = $SEAT_A;" \
    "NULL" "show_seats.booked_by_booking_id"
assert_db booking_db "SELECT status FROM bookings WHERE id = $BOOKING3;" \
    "CANCELLED" "bookings.status"
pass "AVAILABLE, owner NULL, booking CANCELLED - with no sweep in between"

step "a different user can actually buy the seat"
register_user "c3b"; TOKEN3B="$TOKEN"
hold_seats "$TOKEN3B" "$SEAT_A"; BOOKING3B="$BOOKING"
confirm_for_real "$TOKEN3B" "$BOOKING3B"
expect_status 200 "the cancelled seat can be sold to somebody else"
assert_db event_db "SELECT booked_by_booking_id FROM show_seats WHERE id = $SEAT_A;" \
    "$BOOKING3B" "show_seats.booked_by_booking_id"
pass "booking $BOOKING3B owns seat $SEAT_A"

# ---------------------------------------------------------------------------

banner "ALL THREE CASES PASSED"
if [ "$ARMED" = "yes" ]; then
    echo "  event-service was ARMED: every case reproduced the orphan first."
else
    echo "  event-service was NOT ARMED: no timeouts, so no orphan was reproduced."
    echo "  The final-state assertions passed anyway, which is what that run is for."
    echo "  Re-run with --app.fault.book-seats-delay=8s for the real reproduction."
fi
echo "  $REQUESTS gateway requests."
echo
