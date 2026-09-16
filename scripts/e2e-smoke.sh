#!/usr/bin/env bash
#
# e2e-smoke.sh - the regression net for weeks 5 and 6.
#
#   ./scripts/e2e-smoke.sh
#
# Walks one user through the whole platform THROUGH THE GATEWAY ON 8080 and
# nothing else: register, log in, read a public seat map, hold seats, replay the
# Idempotency-Key, lose a race to another user, confirm, watch the seat map
# change, receive the confirmation email, cancel a second hold, and get turned
# away from the three doors that must stay shut.
#
# It prints one PASS line per step and exits non-zero on the first unexpected
# response, naming the step and what it actually got.
#
# ---------------------------------------------------------------------------
# WHY EVERYTHING GOES THROUGH 8080
# ---------------------------------------------------------------------------
# Services stay directly reachable on 8081-8083, where X-User-Id is still taken
# on trust - the load tests use that path deliberately. This script must not.
# Half of what it checks is the gateway's own behaviour: the header strip, the
# public/protected split, the unrouted /api/internal/**. A step that talked to
# 8083 directly would still go green after the gateway stopped doing any of it.
#
# The two exceptions are not gateway traffic and are not meant to be:
#   * reset-fixtures.sh, which talks to MySQL and Redis in their containers
#   * MailHog's API on 8025, which is the mail server, not the platform
#
# ---------------------------------------------------------------------------
# WHY IT SEEDS THROUGH reset-fixtures.sh AND NOT THE ADMIN API
# ---------------------------------------------------------------------------
# POST /api/auth/register creates a USER. There is no endpoint that creates an
# ADMIN and no ADMIN is seeded, so there is no way for this script to obtain a
# token that /api/admin/** would accept - the admin endpoints are unreachable
# from here by design, not by oversight.
#
# reset-fixtures.sh truncates and re-seeds from event-service's own
# DemoDataSeeder, which resets AUTO_INCREMENT, so the show id and the seat ids
# come out identical on every run. That is what makes this script deterministic
# rather than merely repeatable. It is also destructive and local-dev only: it
# empties booking_db and event_db. Do not point this at anything you care about.
#
# ---------------------------------------------------------------------------
# REQUEST BUDGET - READ THIS BEFORE ADDING A STEP
# ---------------------------------------------------------------------------
# RateLimitFilter is a token bucket per client IP per route policy. Every path
# this script touches falls under the default policy:
#
#     capacity 40 (burst), refill 120/minute (2 tokens/second)
#
# so a run that fires more than 40 requests faster than it earns them back gets
# a 429 and fails on a step that is otherwise correct.
#
#     THIS SCRIPT MAKES 17 REQUESTS THROUGH THE GATEWAY.
#
#     step  2   3   register + log in user A
#     step  3   1   public seat map
#     step  4   1   hold
#     step  5   2   replay + list bookings
#     step  6   3   register + log in user B, then hold
#     step  7   1   confirm
#     step  8   1   seat map again
#     step  9   0   MailHog, not the gateway
#     step 10   3   hold, cancel, re-hold
#     step 11   1   no token
#     step 12   1   spoofed header
#     step 13   1   unrouted path
#
# That is 17 of a 40 burst, with no wait needed anywhere. If you add steps and
# that number climbs toward 40, the limiter is a real constraint you have hit,
# not a flake: either drop a request or space the run out. Do not "fix" it by
# raising the limit - the limit is the thing under test elsewhere. The printed
# total at the end is the authority; keep the number above in step with it.
#
# ---------------------------------------------------------------------------
# REQUIREMENTS
# ---------------------------------------------------------------------------
#   bash, curl, jq
#   docker, with the infra from docker-compose.infra.yml up
#   all five services running: gateway 8080, auth 8081, event 8082,
#   booking 8083, notification 8085
#   event-service's jar built, for reset-fixtures.sh's throwaway seeder JVM
# ---------------------------------------------------------------------------
set -Eeuo pipefail

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8080}"
MAILHOG_URL="${MAILHOG_URL:-http://localhost:8025}"

# Per request. Generous: confirm makes a synchronous call to event-service.
CURL_TIMEOUT="${CURL_TIMEOUT:-15}"

# Step 9. The outbox publisher polls every 2s and the consumer is not instant,
# so a few seconds is normal and 30 is a failure, not impatience.
MAIL_TIMEOUT_SECONDS="${MAIL_TIMEOUT_SECONDS:-30}"
MAIL_POLL_INTERVAL="${MAIL_POLL_INTERVAL:-1}"

PASSWORD="${E2E_PASSWORD:-SmokeTest123!}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly REPO_ROOT
readonly FIXTURES="$REPO_ROOT/load-tests/reset-fixtures.sh"

# Unique per run, so the MailHog search in step 9 can never match an email left
# by an earlier run. Booking ids cannot do that job: reset-fixtures.sh resets
# AUTO_INCREMENT, so every run's first booking is id 1 again.
RUN_ID="$(date +%s)-$$"
readonly RUN_ID

WORK_DIR="$(mktemp -d -t e2e-smoke.XXXXXX)"
readonly WORK_DIR
readonly BODY_FILE="$WORK_DIR/body"
readonly ERR_FILE="$WORK_DIR/err"
readonly FIXTURES_LOG="$WORK_DIR/reset-fixtures.log"
trap 'rm -rf "$WORK_DIR"' EXIT

REQUESTS=0
STEP=0
STEP_NAME=""
HTTP_STATUS=""
HTTP_BODY=""

# ---------------------------------------------------------------------------
# OUTPUT
# ---------------------------------------------------------------------------

step() {
    STEP="$1"
    STEP_NAME="$2"
}

pass() {
    printf 'PASS  step %2s  %-44s %s\n' "$STEP" "$STEP_NAME" "$1"
}

# Every failure exits here, so the exit status and the explanation can never be
# out of step with each other. $1 is what was expected; the actual response is
# added from whatever the last request left behind.
fail() {
    printf '\n' >&2
    printf 'FAIL  step %2s  %s\n' "$STEP" "$STEP_NAME" >&2
    printf '        expected: %s\n' "$1" >&2
    if [ -n "$HTTP_STATUS" ]; then
        printf '        actual:   HTTP %s\n' "$HTTP_STATUS" >&2
        printf '        body:     %s\n' "$(printf '%s' "$HTTP_BODY" | head -c 800)" >&2
    fi
    printf '\n' >&2
    printf 'Stopped at step %s after %s gateway requests.\n' "$STEP" "$REQUESTS" >&2
    exit 1
}

# A failure with no HTTP response behind it - a missing tool, an unparseable
# fixture, a missing email. Same shape, without the misleading "actual: HTTP".
die() {
    HTTP_STATUS=""
    fail "$1"
}

# ---------------------------------------------------------------------------
# HTTP
# ---------------------------------------------------------------------------

# req METHOD PATH [extra curl args...]
#
# Leaves the status in HTTP_STATUS and the body in HTTP_BODY. Never fails the
# script itself: a connection refused becomes status 000 with curl's message as
# the body, so the step's own assertion reports it in context instead of
# pipefail killing the run with nothing on screen.
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

expect_status() {
    [ "$HTTP_STATUS" = "$1" ] || fail "HTTP $1 - $2"
}

# Reads one value out of the last response. Fails the step with the body in view
# rather than returning an empty string that some later comparison would report
# as an unrelated mismatch.
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
# MAIL DECODING
# ---------------------------------------------------------------------------
# MailHog hands back what was transmitted, not what was typed. The confirmation
# carries an em dash and a rupee sign, so the subject arrives as an RFC 2047
# encoded-word and the body as quoted-printable or base64. Asserting on the raw
# strings would be asserting on the encoding, and would start failing the day
# the mailer picks a different one for the same text.

decode_qp() {
    # Soft line breaks first (a trailing "=" joins the next line and is not a
    # literal), then =XX. Backslashes are escaped before %b so a literal one in
    # the body cannot be read as an escape sequence.
    sed -e ':a' -e 'N' -e '$!ba' -e 's/=\r\{0,1\}\n//g' \
        | sed -e 's/\\/\\\\/g' -e 's/=\([0-9A-Fa-f][0-9A-Fa-f]\)/\\x\1/g' \
        | while IFS= read -r line || [ -n "$line" ]; do printf '%b\n' "$line"; done
}
# The "|| [ -n "$line" ]" is load-bearing, not defensive. A header is decoded
# from a single line with NO trailing newline, so read returns non-zero on it
# and a plain "while read" would drop the only line there was and hand back an
# empty subject.

# decode_body CONTENT_TRANSFER_ENCODING < raw
decode_body() {
    case "$(printf '%s' "${1:-}" | tr '[:upper:]' '[:lower:]')" in
        base64)           base64 -d 2>/dev/null || true ;;
        quoted-printable) decode_qp ;;
        *)                cat ;;
    esac
}

# One RFC 2047 encoded-word, or the token unchanged if it is not one.
decode_word() {
    local raw="$1" encoding text
    case "$raw" in
        "=?"*"?=")
            encoding="$(printf '%s' "$raw" | sed -n 's/^=?[^?]*?\([^?]*\)?.*/\1/p')"
            text="$(printf '%s' "$raw" | sed -n 's/^=?[^?]*?[^?]*?\(.*\)?=$/\1/p')"
            case "$(printf '%s' "$encoding" | tr '[:upper:]' '[:lower:]')" in
                b) printf '%s' "$text" | base64 -d 2>/dev/null || printf '%s' "$raw" ;;
                # In Q, "_" is a space; the rest is quoted-printable. The
                # underscores go first, because a literal one arrives as =5F.
                q) printf '%s' "$text" | tr '_' ' ' | decode_qp ;;
                *) printf '%s' "$raw" ;;
            esac
            ;;
        *) printf '%s' "$raw" ;;
    esac
}

# A whole header value.
#
# An encoded-word is capped at 75 characters, so a subject longer than that is
# split into SEVERAL of them separated by folding whitespace - which is dropped
# on decode, not kept, or the title would come back with a space inside it. The
# real subject arrives as two: "...confirmed =E2=80=94 C" then "oldplay - Music
# of the Spheres". A decoder that handled only one word returned an empty
# string here, which is how this was found.
decode_header() {
    local out="" token
    # "|| [ -n "$token" ]" for the same reason as in decode_qp: the split output
    # has no trailing newline, so the final word - or the only word, when the
    # header is short enough not to be split at all - would be dropped.
    while IFS= read -r token || [ -n "$token" ]; do
        out="$out$(decode_word "$token")"
    done < <(printf '%s' "$1" | sed -e 's/?=[[:space:]]\{1,\}=?/?=\n=?/g')
    printf '%s' "$out"
}

# ---------------------------------------------------------------------------

echo "=================================================================="
echo "  BookMySeat end-to-end smoke test"
echo "=================================================================="
echo "  gateway   $GATEWAY_URL"
echo "  mailhog   $MAILHOG_URL"
echo "  run id    $RUN_ID"
echo

step 0 "preflight"
command -v jq   >/dev/null 2>&1 || die "jq on PATH (https://jqlang.github.io/jq/)"
command -v curl >/dev/null 2>&1 || die "curl on PATH"
[ -x "$FIXTURES" ] || die "an executable $FIXTURES"


# ===========================================================================
# 1. A known state, and the ids that go with it
# ===========================================================================
step 1 "reset fixtures, read SHOW_ID and seat ids"

echo "      running reset-fixtures.sh (starts a seeder JVM, takes a moment)..."
if ! "$FIXTURES" >"$FIXTURES_LOG" 2>&1; then
    echo >&2
    echo "--- last 30 lines of reset-fixtures.sh ---" >&2
    tail -30 "$FIXTURES_LOG" >&2
    echo "------------------------------------------" >&2
    die "reset-fixtures.sh to succeed"
fi

SHOW_ID="$(awk '$1 == "SHOW_ID" { print $2; exit }' "$FIXTURES_LOG")"
case "$SHOW_ID" in
    "" | *[!0-9]*) die "a numeric SHOW_ID line in reset-fixtures.sh's output, got '${SHOW_ID:-<nothing>}'" ;;
esac

# The block reset-fixtures.sh prints under "AVAILABLE seat ids for show N",
# wrapped across several indented lines and ended by a blank one.
read -r -a SEATS <<<"$(awk '
    /AVAILABLE seat ids for show/ { grab = 1; next }
    grab && NF == 0               { exit }
    grab                          { for (i = 1; i <= NF; i++) printf "%s ", $i }
' "$FIXTURES_LOG")"

# 5 is what the steps below consume: 2 held and confirmed, 2 held and cancelled,
# 1 for the header-spoofing check, which must not collide with any of them.
[ "${#SEATS[@]}" -ge 5 ] \
    || die "at least 5 AVAILABLE seat ids from reset-fixtures.sh, got ${#SEATS[@]}"
for seat in "${SEATS[@]:0:5}"; do
    case "$seat" in
        "" | *[!0-9]*) die "numeric seat ids from reset-fixtures.sh, got '$seat'" ;;
    esac
done

SEAT_A="${SEATS[0]}"
SEAT_B="${SEATS[1]}"
SEAT_C="${SEATS[2]}"
SEAT_D="${SEATS[3]}"
SEAT_E="${SEATS[4]}"

pass "show $SHOW_ID, seats $SEAT_A $SEAT_B $SEAT_C $SEAT_D $SEAT_E"


# ===========================================================================
# 2. Register and log in
# ===========================================================================
step 2 "register and log in user A"

EMAIL_A="e2e-a-$RUN_ID@example.com"

req POST /api/auth/register \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$EMAIL_A\",\"password\":\"$PASSWORD\",\"fullName\":\"Smoke Test A\"}"
expect_status 201 "a new user is created"
USER_A_ID="$(body_field '.id')"
[ "$(json '.role')" = "USER" ] \
    || fail "role USER - registration cannot mint an ADMIN, which is why this script seeds through reset-fixtures.sh"

req POST /api/auth/login \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$EMAIL_A\",\"password\":\"$PASSWORD\"}"
expect_status 200 "the credentials just registered are accepted"
TOKEN_A="$(body_field '.accessToken')"
AUTH_A="Authorization: Bearer $TOKEN_A"

pass "user A id=$USER_A_ID role=USER, token issued"


# ===========================================================================
# 3. The seat map is public
# ===========================================================================
# Not a formality. The frontend polls the seat map on a show page before anyone
# has logged in, so a token requirement creeping onto this path breaks the first
# screen a visitor sees. JwtAuthenticationFilter lets GET /api/shows/*/seats
# through with no Authorization header; this is the test of that.
step 3 "GET the seat map with NO token"

req GET "/api/shows/$SHOW_ID/seats"
expect_status 200 "the seat map is public - the frontend polls it before login"
TOTAL_SEATS="$(body_field '.totalSeats')"
AVAILABLE_BEFORE="$(body_field '.availableSeats')"

pass "200 unauthenticated, $AVAILABLE_BEFORE/$TOTAL_SEATS available"


# ===========================================================================
# 4. Hold two seats
# ===========================================================================
step 4 "hold seats $SEAT_A and $SEAT_B"

IDEM_KEY="$(uuid)"
[ -n "$IDEM_KEY" ] || die "a UUID for the Idempotency-Key"

HOLD_BODY="{\"showId\":$SHOW_ID,\"seatIds\":[$SEAT_A,$SEAT_B]}"

req POST /api/bookings/hold \
    -H "$AUTH_A" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $IDEM_KEY" \
    -d "$HOLD_BODY"
expect_status 201 "the seats are free, so the hold is taken"
BOOKING_ID="$(body_field '.id')"
[ "$(json '.status')" = "PENDING" ] || fail "status PENDING"
[ "$(json '.userId')" = "$USER_A_ID" ] || fail "userId $USER_A_ID"
HELD_SEATS="$(json '[.seats[].showSeatId] | sort | join(",")')"
WANT_SEATS="$(printf '%s\n%s\n' "$SEAT_A" "$SEAT_B" | sort -n | paste -sd, -)"
[ "$HELD_SEATS" = "$WANT_SEATS" ] \
    || fail "exactly seats $WANT_SEATS on the booking, got $HELD_SEATS"

pass "booking $BOOKING_ID PENDING, expires $(json '.expiresAt')"


# ===========================================================================
# 5. Replaying the key returns the same booking and creates nothing
# ===========================================================================
# The status code alone is not the assertion. A 200 with a DIFFERENT booking id
# would mean the key was ignored and a second booking was created - the exact
# double-booking the header exists to prevent - so the id is compared, and then
# the caller's own booking list is counted to prove no second row was written.
step 5 "replay the same Idempotency-Key"

req POST /api/bookings/hold \
    -H "$AUTH_A" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $IDEM_KEY" \
    -d "$HOLD_BODY"
expect_status 200 "200, not 201 - a replay creates nothing"
REPLAY_ID="$(body_field '.id')"
[ "$REPLAY_ID" = "$BOOKING_ID" ] \
    || fail "booking $BOOKING_ID again, got $REPLAY_ID - the key was ignored and a second booking was created"

req GET /api/bookings -H "$AUTH_A"
expect_status 200 "the caller's bookings"
BOOKING_COUNT="$(json 'length')"
[ "$BOOKING_COUNT" = "1" ] \
    || fail "exactly 1 booking row for user A, found $BOOKING_COUNT"

pass "200, same booking $REPLAY_ID, 1 booking row total"


# ===========================================================================
# 6. A second user cannot take a held seat
# ===========================================================================
step 6 "user B holds seat $SEAT_A"

EMAIL_B="e2e-b-$RUN_ID@example.com"

req POST /api/auth/register \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$EMAIL_B\",\"password\":\"$PASSWORD\",\"fullName\":\"Smoke Test B\"}"
expect_status 201 "a second user is created"
USER_B_ID="$(body_field '.id')"

req POST /api/auth/login \
    -H 'Content-Type: application/json' \
    -d "{\"email\":\"$EMAIL_B\",\"password\":\"$PASSWORD\"}"
expect_status 200 "user B can log in"
TOKEN_B="$(body_field '.accessToken')"

req POST /api/bookings/hold \
    -H "Authorization: Bearer $TOKEN_B" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $(uuid)" \
    -d "{\"showId\":$SHOW_ID,\"seatIds\":[$SEAT_A,$SEAT_E]}"
expect_status 409 "seat $SEAT_A is held by user A, so the whole hold is refused"

# The body must NAME the seat, not just say no. conflictingSeatIds is what lets
# a frontend grey out the taken seat instead of parsing an English sentence.
CONFLICTS="$(json '[.conflictingSeatIds[]] | join(",")')"
printf '%s\n' "${CONFLICTS//,/$'\n'}" | grep -qx "$SEAT_A" \
    || fail "conflictingSeatIds to contain $SEAT_A, got [$CONFLICTS]"

# All or nothing: seat E was free and must NOT have been taken on the way past,
# so it must not be listed either.
if printf '%s\n' "${CONFLICTS//,/$'\n'}" | grep -qx "$SEAT_E"; then
    fail "seat $SEAT_E to be free and unlisted, but it is in conflictingSeatIds [$CONFLICTS]"
fi

pass "409, user B (id=$USER_B_ID) refused, conflicts=[$CONFLICTS]"


# ===========================================================================
# 7. Confirm
# ===========================================================================
step 7 "confirm booking $BOOKING_ID"

req POST "/api/bookings/$BOOKING_ID/confirm" -H "$AUTH_A"
expect_status 200 "the holds are still user A's, so confirm succeeds"
[ "$(json '.status')" = "CONFIRMED" ] || fail "status CONFIRMED"
[ "$(json '.expiresAt')" = "null" ] \
    || fail "expiresAt null - a confirmed booking does not expire"

pass "200 CONFIRMED, expiresAt cleared"


# ===========================================================================
# 8. The seat map now says BOOKED
# ===========================================================================
# The cross-service assertion: booking-service's confirm wrote through to
# event-service's show_seats, and the public read model reflects it.
step 8 "seat map shows both seats BOOKED"

req GET "/api/shows/$SHOW_ID/seats"
expect_status 200 "the seat map after confirm"

for seat in "$SEAT_A" "$SEAT_B"; do
    seat_status="$(json "[.rows[].seats[] | select(.id == $seat) | .status] | first")"
    [ "$seat_status" = "BOOKED" ] \
        || fail "seat $seat to be BOOKED, the map says '${seat_status:-<not in the map>}'"
done

# The seat labels the confirmation email must carry, spelled the way
# event-service spells them, read off the same map rather than assumed.
LABEL_A="$(json "[.rows[].seats[] | select(.id == $SEAT_A) | .rowLabel + (.seatNumber|tostring)] | first")"
LABEL_B="$(json "[.rows[].seats[] | select(.id == $SEAT_B) | .rowLabel + (.seatNumber|tostring)] | first")"
AVAILABLE_AFTER="$(body_field '.availableSeats')"
[ "$AVAILABLE_AFTER" = "$((AVAILABLE_BEFORE - 2))" ] \
    || fail "availableSeats to drop by exactly 2 from $AVAILABLE_BEFORE, got $AVAILABLE_AFTER"

pass "$LABEL_A and $LABEL_B BOOKED, available $AVAILABLE_BEFORE -> $AVAILABLE_AFTER"


# ===========================================================================
# 9. The confirmation email arrives
# ===========================================================================
# Kafka, the outbox publisher and the consumer are all asynchronous, so this
# polls. It searches by RECIPIENT, which is unique per run: booking ids are not,
# because reset-fixtures.sh resets AUTO_INCREMENT and every run's first booking
# is id 1 again. It does not clear MailHog - emptying a developer's inbox to
# make an assertion easier is not this script's call.
#
# MailHog is NOT gateway traffic and spends none of the request budget.
step 9 "the confirmation email reaches MailHog"

MAIL_JSON=""
deadline=$(( $(date +%s) + MAIL_TIMEOUT_SECONDS ))
while :; do
    MAIL_JSON="$(curl -sS --max-time 5 --get "$MAILHOG_URL/api/v2/search" \
        --data-urlencode 'kind=to' --data-urlencode "query=$EMAIL_A" 2>/dev/null || true)"
    if [ "$(printf '%s' "$MAIL_JSON" | jq -r '.total // 0' 2>/dev/null || echo 0)" -ge 1 ]; then
        break
    fi
    if [ "$(date +%s)" -ge "$deadline" ]; then
        die "a confirmation email for $EMAIL_A within ${MAIL_TIMEOUT_SECONDS}s. Nothing arrived.
                  Check: notification-service is running, Kafka is up, the
                  booking.confirmed topic exists, and MailHog is reachable at $MAILHOG_URL"
    fi
    sleep "$MAIL_POLL_INTERVAL"
done

RAW_SUBJECT="$(printf '%s' "$MAIL_JSON" | jq -r '.items[0].Content.Headers.Subject[0] // ""')"
SUBJECT="$(decode_header "$RAW_SUBJECT")"
TRANSFER_ENCODING="$(printf '%s' "$MAIL_JSON" \
    | jq -r '.items[0].Content.Headers["Content-Transfer-Encoding"][0] // ""')"
MAIL_BODY="$(printf '%s' "$MAIL_JSON" | jq -r '.items[0].Content.Body // ""' \
    | decode_body "$TRANSFER_ENCODING")"

case "$SUBJECT" in
    "Your booking is confirmed"*) ;;
    *) die "a subject starting 'Your booking is confirmed', got '$SUBJECT' (raw: '$RAW_SUBJECT')" ;;
esac

for label in "$LABEL_A" "$LABEL_B"; do
    printf '%s' "$MAIL_BODY" | grep -qF "$label" \
        || die "the body to contain the seat label '$label'. Body was:
$(printf '%s' "$MAIL_BODY" | head -c 600)"
done

pass "subject '$SUBJECT', body names $LABEL_A and $LABEL_B"


# ===========================================================================
# 10. Cancelling releases the holds immediately
# ===========================================================================
# "Immediately" cannot be read off the seat map. A held seat still reads
# AVAILABLE there BY DESIGN - a hold is a Redis key with a TTL and show_seats
# has no HELD status - so the map would say AVAILABLE before the cancel and
# after it, and this step would keep passing with the release deleted entirely.
#
# The assertion that actually separates an immediate release from a ten minute
# wait is that the seats can be held AGAIN, right now, with a fresh key. A 409
# on that third request means the holds outlived the cancel.
step 10 "cancel releases seats $SEAT_C and $SEAT_D at once"

req POST /api/bookings/hold \
    -H "$AUTH_A" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $(uuid)" \
    -d "{\"showId\":$SHOW_ID,\"seatIds\":[$SEAT_C,$SEAT_D]}"
expect_status 201 "a second hold, on two free seats"
CANCEL_ID="$(body_field '.id')"

req DELETE "/api/bookings/$CANCEL_ID" -H "$AUTH_A"
expect_status 200 "200 with the cancelled booking, not 204 - the row still exists"
[ "$(json '.status')" = "CANCELLED" ] || fail "status CANCELLED"

req POST /api/bookings/hold \
    -H "$AUTH_A" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $(uuid)" \
    -d "{\"showId\":$SHOW_ID,\"seatIds\":[$SEAT_C,$SEAT_D]}"
expect_status 201 "seats $SEAT_C and $SEAT_D holdable again straight away - a 409 means the cancel left the Redis holds to expire on their own"

pass "$CANCEL_ID CANCELLED, both seats re-held at once"


# ===========================================================================
# 11. A protected path needs a token
# ===========================================================================
step 11 "GET /api/bookings with no token"

req GET /api/bookings
expect_status 401 "401 - /api/bookings is not public"

pass "401, error='$(json '.error')'"


# ===========================================================================
# 12. THE SECURITY REGRESSION TEST
# ===========================================================================
# User A's real token, plus a hand-written X-User-Id claiming to be user B.
#
# JwtAuthenticationFilter strips every inbound X-User-* header in any letter
# case and then sets X-User-Id from the token's subject. booking-service takes
# whatever arrives at face value, so if that strip ever stops happening - a
# filter reordered, an order value changed, a rewrite that quietly drops it -
# this request creates a booking for user B while carrying user A's token.
#
# THE STATUS CODE CANNOT SEE THAT. The response is 201 either way; only the
# owner differs. That is why the assertion is on userId, and why a step that
# checked the status alone would be worth nothing here.
step 12 "spoofed X-User-Id must be ignored"

req POST /api/bookings/hold \
    -H "$AUTH_A" \
    -H "X-User-Id: $USER_B_ID" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $(uuid)" \
    -d "{\"showId\":$SHOW_ID,\"seatIds\":[$SEAT_E]}"
expect_status 201 "the hold itself succeeds; the question is who owns it"

SPOOFED_OWNER="$(body_field '.userId')"
[ "$SPOOFED_OWNER" = "$USER_A_ID" ] \
    || fail "the booking to belong to user A ($USER_A_ID), the token's subject. It belongs to $SPOOFED_OWNER.
                  The gateway has stopped stripping inbound X-User-* headers: a client can
                  now book as anyone by setting one. Check JwtAuthenticationFilter's order
                  (-100) and that RateLimitFilter (-200) still runs before it."

pass "X-User-Id: $USER_B_ID ignored, booking owned by $USER_A_ID"


# ===========================================================================
# 13. /api/internal/** is not routed
# ===========================================================================
# event-service's internal seat write has no authentication of its own - it
# trusts that only booking-service can reach it. There is no gateway route for
# /api/internal/**, so the gateway answers 404 itself and nothing downstream is
# touched. A 200, 401 or 403 here would all mean a route now exists.
step 13 "POST /api/internal/shows/1/seats/book"

req POST /api/internal/shows/1/seats/book \
    -H "$AUTH_A" \
    -H 'Content-Type: application/json' \
    -d '{"seatIds":[1]}'
expect_status 404 "404 - /api/internal/** must have no route. Anything else means one was added"

pass "404, unrouted at the gateway"


# ===========================================================================
echo
echo "=================================================================="
echo "  ALL 13 STEPS PASSED"
echo "=================================================================="
printf '  gateway requests   %s  (burst capacity 40, refill 120/min)\n' "$REQUESTS"
printf '  show / user A      %s / %s\n' "$SHOW_ID" "$USER_A_ID"
printf '  confirmed booking  %s  seats %s, %s\n' "$BOOKING_ID" "$LABEL_A" "$LABEL_B"
echo "=================================================================="
