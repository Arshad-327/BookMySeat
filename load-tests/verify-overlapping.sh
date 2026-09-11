#!/usr/bin/env bash
#
# verify-overlapping.sh - grade an overlapping-seats.js run from outside.
#
#   ./load-tests/verify-overlapping.sh <file holding the stdout of overlapping-seats.js>
#
# Run it within ten minutes of the burst, while the holds are still live.
#
# ---------------------------------------------------------------------------
# WHY THIS IS A SEPARATE SCRIPT
# ---------------------------------------------------------------------------
# overlapping-seats.js reports what it was told over HTTP. That is a claim, not
# evidence: a 201 says the caller was told it owns its seats, not that it does.
# This script decides from the stores themselves - Redis for who holds each seat,
# MySQL for which bookings survived. It takes only two things from the k6 output:
# the plan (which seats, and which user asked for which set), and the HTTP
# outcomes, which it cross-checks against the stores instead of trusting. A run
# that grades itself is weaker evidence than one checked from outside - the same
# reason the single-seat numbers were checked against SQL rather than taken from
# the k6 summary.
#
# ---------------------------------------------------------------------------
# THE VERDICTS
# ---------------------------------------------------------------------------
# For every pair, where user A asked for {X, Y} and user B for {Y, Z}:
#
#   1. Exactly one user holds their COMPLETE set: a PENDING booking in booking_db
#      whose booking_seats are exactly that set, with every one of those seats
#      held in Redis by that booking's id.
#   2. The other user got a 409 over HTTP, and left no booking in booking_db.
#   3. The winner's HTTP response agrees with the stores: 201, same booking id.
#
# And for every seat:hold:* key in Redis:
#
#   4. No orphan. A key's value must be the id of a booking that exists, is
#      PENDING and claims that seat. A seat held by anything else - a booking id
#      that does not exist, or one that is not PENDING - is an orphan: exactly the
#      failure an all-or-nothing hold exists to prevent.
#
# A seat held by NOBODY is not a failure. In a correct pair the loser's other
# seat is free; it is listed per pair so that is visible.
#
# Exit codes:
#   0  every verdict holds
#   1  a verdict failed - the offending pairs and seats are named
#   2  the run could not be verified at all (bad input, a store unreachable, holds
#      already expired). Distinct from 1: nothing was graded.
#
# Read-only. It never writes to Redis or MySQL.
# ---------------------------------------------------------------------------
set -Eeuo pipefail

MYSQL_CONTAINER="${MYSQL_CONTAINER:-bookmyseat-mysql}"
DB_USERNAME="${DB_USERNAME:-root}"
DB_PASSWORD="${DB_PASSWORD:-root}"
REDIS_CONTAINER="${REDIS_CONTAINER:-bookmyseat-redis}"

cannot_verify() {
    echo >&2
    echo "CANNOT VERIFY: $*" >&2
    echo >&2
    exit 2
}

step() { echo; echo "== $*"; }

# Same shape as reset-fixtures.sh: tab-separated rows, no decoration, and
# MYSQL_PWD rather than -p so the password stays out of argv.
mysql_q() {
    local database="$1" sql="$2"
    docker exec -e MYSQL_PWD="$DB_PASSWORD" "$MYSQL_CONTAINER" \
        mysql -u"$DB_USERNAME" --batch --skip-column-names --database="$database" -e "$sql"
}

container_running() {
    [ "$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null || echo false)" = "true" ]
}

[ "$#" -eq 1 ] || cannot_verify "usage: $0 <file holding the stdout of overlapping-seats.js>"
RUN_OUTPUT="$1"
[ -f "$RUN_OUTPUT" ] || cannot_verify "no such file: $RUN_OUTPUT"

WORK="$(mktemp -d -t verify-overlapping.XXXXXX)"
readonly WORK
trap 'rm -rf "$WORK"' EXIT

# ---------------------------------------------------------------------------
# 1. The run's own account: the plan, and what HTTP reported
# ---------------------------------------------------------------------------

step "Reading the plan and the reported HTTP outcomes from $RUN_OUTPUT"

# Output captured through Docker on Windows can carry CRLF; the parser must not see \r.
tr -d '\r' < "$RUN_OUTPUT" > "$WORK/run.txt"

grep -q '^OVERLAP-END ' "$WORK/run.txt" || cannot_verify "$RUN_OUTPUT has no OVERLAP-END line.
  It is not the complete stdout of overlapping-seats.js: setup() failed, the run
  was interrupted, or stderr was saved instead of stdout."

pairs="$(sed -n 's/^OVERLAP-END pairs=\([0-9][0-9]*\)$/\1/p' "$WORK/run.txt" | tail -1)"
[ -n "$pairs" ] || cannot_verify "the OVERLAP-END line in $RUN_OUTPUT is malformed."

# "PREFIX k=v k=v ..." lines -> tab-separated columns in the order named.
fields_to_tsv() {
    local prefix="$1"; shift
    awk -v prefix="$prefix" -v names="$*" '
        BEGIN { n = split(names, want, " ") }
        $1 == prefix {
            split("", value)
            for (i = 2; i <= NF; i++) {
                eq = index($i, "=")
                value[substr($i, 1, eq - 1)] = substr($i, eq + 1)
            }
            line = ""
            for (i = 1; i <= n; i++) {
                line = line (i > 1 ? "\t" : "") ((want[i] in value) ? value[want[i]] : "")
            }
            print line
        }' "$WORK/run.txt"
}

fields_to_tsv OVERLAP-PLAN pair show user_a seats_a user_b seats_b > "$WORK/plan.tsv"
fields_to_tsv OVERLAP-RESULT pair side user status booking conflicting > "$WORK/results.tsv"

plan_lines="$(wc -l < "$WORK/plan.tsv" | tr -d ' ')"
result_lines="$(wc -l < "$WORK/results.tsv" | tr -d ' ')"
[ "$plan_lines" = "$pairs" ] \
    || cannot_verify "expected $pairs OVERLAP-PLAN lines, found $plan_lines. Was output from more than one run saved to the same file?"
[ "$result_lines" = "$((pairs * 2))" ] \
    || cannot_verify "expected $((pairs * 2)) OVERLAP-RESULT lines, found $result_lines."

show_ids="$(cut -f2 "$WORK/plan.tsv" | sort -u)"
[ "$(printf '%s\n' "$show_ids" | wc -l | tr -d ' ')" = "1" ] \
    || cannot_verify "the plan spans more than one show: $(echo $show_ids)"
SHOW_ID="$show_ids"

echo "  $pairs pairs on show $SHOW_ID, $result_lines requests reported"

# ---------------------------------------------------------------------------
# 2. Redis: who holds each seat
# ---------------------------------------------------------------------------

step "Reading every seat:hold:* key and its value from $REDIS_CONTAINER"

container_running "$REDIS_CONTAINER" || cannot_verify "$REDIS_CONTAINER is not running."
[ "$(docker exec "$REDIS_CONTAINER" redis-cli PING 2>/dev/null)" = "PONG" ] \
    || cannot_verify "$REDIS_CONTAINER does not answer PING."

# SCAN, never KEYS, then GET each key - all inside the container. A key that
# expires between the SCAN and its GET comes back empty and counts as not held.
docker exec "$REDIS_CONTAINER" sh -c \
    'redis-cli --scan --pattern "seat:hold:*" | while read -r key; do printf "%s %s\n" "$key" "$(redis-cli GET "$key")"; done' \
    > "$WORK/holds.raw" || cannot_verify "reading seat holds from $REDIS_CONTAINER failed."

# seat:hold:{showId}:{seatId} {bookingId}  ->  showId  seatId  bookingId
awk 'NF == 2 { n = split($1, part, ":"); if (n == 4) print part[3] "\t" part[4] "\t" $2 }' \
    "$WORK/holds.raw" > "$WORK/holds.tsv"

echo "  $(wc -l < "$WORK/holds.tsv" | tr -d ' ') hold key(s)"

# ---------------------------------------------------------------------------
# 3. MySQL: which bookings survived, and which seats each claims
# ---------------------------------------------------------------------------

step "Reading bookings and booking_seats from booking_db in $MYSQL_CONTAINER"

container_running "$MYSQL_CONTAINER" || cannot_verify "$MYSQL_CONTAINER is not running."

mysql_q booking_db "
    SELECT b.id,
           b.user_id,
           b.status,
           IFNULL(DATE_FORMAT(b.expires_at, '%Y-%m-%dT%H:%i:%s.%fZ'), '-'),
           b.show_id,
           IFNULL(GROUP_CONCAT(bs.show_seat_id ORDER BY bs.show_seat_id SEPARATOR ','), '-')
      FROM bookings b
      LEFT JOIN booking_seats bs ON bs.booking_id = b.id
     GROUP BY b.id, b.user_id, b.status, b.expires_at, b.show_id
     ORDER BY b.id;" > "$WORK/bookings.tsv" || cannot_verify "querying booking_db failed."

echo "  $(wc -l < "$WORK/bookings.tsv" | tr -d ' ') booking(s)"

# Grading needs live holds. A PENDING booking already past its expiry may have lost
# its Redis keys to the TTL, and a verdict on that would describe the TTL, not the
# hold script. Compared against an explicit UTC instant, never SQL NOW().
now="$(date -u '+%Y-%m-%dT%H:%M:%S.%6NZ')"
expired="$(awk -F '\t' -v now="$now" \
    '$3 == "PENDING" && $4 != "-" && $4 <= now { print "    booking " $1 " (user " $2 ") expired at " $4 }' \
    "$WORK/bookings.tsv")"
[ -z "$expired" ] || cannot_verify "holds have expired since the run - verify within ten minutes of the burst.
$expired
  (checked at $now)"

# ---------------------------------------------------------------------------
# 4. Verdicts
# ---------------------------------------------------------------------------

VERDICT_AWK="$(cat <<'AWK'
function inList(item, list,    parts, n, i) {
    n = split(list, parts, ",")
    for (i = 1; i <= n; i++) if (parts[i] == item) return 1
    return 0
}

# The booking id if `user` holds exactly `seats`: one PENDING booking in
# booking_db for this show whose booking_seats are exactly that set, and every one
# of those seats held in Redis by that booking. Otherwise "", with the reason in WHY.
function completeBooking(user, seats,    ids, n, id, s, m, j, key) {
    if (!(user in bookingsOfUser)) { WHY = "no booking in booking_db"; return "" }
    n = split(bookingsOfUser[user], ids, ",")
    if (n > 1) { WHY = n " bookings in booking_db (" bookingsOfUser[user] ")"; return "" }
    id = ids[1]
    if (bStatus[id] != "PENDING") { WHY = "booking " id " is " bStatus[id]; return "" }
    if (bShow[id] != show) { WHY = "booking " id " is for show " bShow[id]; return "" }
    if (bSeats[id] != seats) { WHY = "booking " id " claims seats " bSeats[id] ", not " seats; return "" }
    m = split(seats, s, ",")
    for (j = 1; j <= m; j++) {
        key = show ":" s[j]
        if (!(key in holder)) { WHY = "booking " id " exists but seat " s[j] " is not held in Redis"; return "" }
        if (holder[key] != id) { WHY = "booking " id " exists but seat " s[j] " is held by booking " holder[key]; return "" }
    }
    WHY = ""
    return id
}

function fail(pair, message) {
    failures[pair] = failures[pair] "        FAIL  " message "\n"
}

FILENAME == planFile {
    pair = $1
    pairOrder[++pairCount] = pair
    userA[pair] = $3; seatsA[pair] = $4
    userB[pair] = $5; seatsB[pair] = $6
    n = split($4 "," $6, s, ",")
    triple[pair] = ""
    for (i = 1; i <= n; i++) {
        seatPair[s[i]] = pair
        if (!inList(s[i], triple[pair])) triple[pair] = triple[pair] (triple[pair] == "" ? "" : ",") s[i]
    }
    next
}
FILENAME == resultsFile {
    rStatus[$1, $2] = $4; rBooking[$1, $2] = $5
    next
}
FILENAME == holdsFile {
    holder[$1 ":" $2] = $3
    holdShow[++holdCount] = $1; holdSeat[holdCount] = $2; holdValue[holdCount] = $3
    next
}
FILENAME == bookingsFile {
    bStatus[$1] = $3; bShow[$1] = $5; bSeats[$1] = $6
    bookingsOfUser[$2] = ($2 in bookingsOfUser) ? bookingsOfUser[$2] "," $1 : $1
    next
}

END {
    # Verdict 4 first, so each pair's verdict can include its own orphans.
    orphans = 0
    for (i = 1; i <= holdCount; i++) {
        id = holdValue[i]; seat = holdSeat[i]; hs = holdShow[i]
        reason = ""
        if (!(id in bStatus))
            reason = "held by booking " id ", which does not exist"
        else if (bStatus[id] != "PENDING")
            reason = "held by booking " id ", which is " bStatus[id]
        else if (bShow[id] != hs || !inList(seat, bSeats[id]))
            reason = "held by booking " id ", which does not claim this seat"
        if (reason == "") continue
        orphans++
        inPlan = (hs == show && (seat in seatPair))
        orphanLines = orphanLines "  ORPHAN  seat:hold:" hs ":" seat "  " reason "  (" (inPlan ? "pair " seatPair[seat] : "outside the plan") ")\n"
        if (inPlan) fail(seatPair[seat], "orphan: seat " seat " " reason)
    }

    printf "  %-5s %-16s %-16s %-7s %-8s %-6s %s\n", "PAIR", "A user {seats}", "B user {seats}", "WINNER", "BOOKING", "FREE", "VERDICT"
    passed = 0
    for (k = 1; k <= pairCount; k++) {
        p = pairOrder[k]
        a = completeBooking(userA[p], seatsA[p]); whyA = WHY
        b = completeBooking(userB[p], seatsB[p]); whyB = WHY

        winner = "-"; winnerBooking = "-"
        if (a != "" && b != "") {
            # Verdict 1: two complete sets over a shared seat.
            fail(p, "both users hold their complete set: booking " a " and booking " b " (seats " triple[p] ")")
        } else if (a == "" && b == "") {
            # Verdict 1: nobody got a complete set.
            fail(p, "neither user holds a complete set (seats " triple[p] ") - A: " whyA "; B: " whyB)
        } else {
            winner = (a != "") ? "A" : "B"
            loser = (a != "") ? "B" : "A"
            winnerBooking = (a != "") ? a : b
            loserUser = (a != "") ? userB[p] : userA[p]
            loserSeats = (a != "") ? seatsB[p] : seatsA[p]

            # Verdict 3: the winner's HTTP claim must agree with the stores.
            if (rStatus[p, winner] != "201")
                fail(p, "user " winner " holds its seats, but the run reported status " rStatus[p, winner])
            else if (rBooking[p, winner] != winnerBooking)
                fail(p, "user " winner " was told booking " rBooking[p, winner] ", but booking_db and Redis say " winnerBooking)

            # Verdict 2: the loser got a 409 and left nothing behind.
            if (rStatus[p, loser] != "409")
                fail(p, "user " loser " (seats " loserSeats ") got status " rStatus[p, loser] ", not 409")
            if (loserUser in bookingsOfUser)
                fail(p, "user " loser " still has booking(s) " bookingsOfUser[loserUser] " (seats " loserSeats ")")
        }

        free = ""
        n = split(triple[p], s, ",")
        for (i = 1; i <= n; i++) if (!((show ":" s[i]) in holder)) free = free (free == "" ? "" : ",") s[i]
        if (free == "") free = "-"

        verdict = (p in failures) ? "FAIL" : "PASS"
        if (verdict == "PASS") passed++
        printf "  %-5s %-16s %-16s %-7s %-8s %-6s %s\n", p, userA[p] " {" seatsA[p] "}", userB[p] " {" seatsB[p] "}", winner, winnerBooking, free, verdict
        if (p in failures) printf "%s", failures[p]
    }

    print ""
    if (orphanLines != "") printf "%s\n", orphanLines
    printf "  pairs passing      %d of %d\n", passed, pairCount
    printf "  orphaned holds     %d\n", orphans
    printf "  hold keys checked  %d\n", holdCount

    exit ((passed == pairCount && orphans == 0) ? 0 : 1)
}
AWK
)"

step "Verdicts"

verdict_exit=0
awk -F '\t' \
    -v show="$SHOW_ID" \
    -v planFile="$WORK/plan.tsv" \
    -v resultsFile="$WORK/results.tsv" \
    -v holdsFile="$WORK/holds.tsv" \
    -v bookingsFile="$WORK/bookings.tsv" \
    "$VERDICT_AWK" \
    "$WORK/plan.tsv" "$WORK/results.tsv" "$WORK/holds.tsv" "$WORK/bookings.tsv" \
    || verdict_exit=$?

echo
case "$verdict_exit" in
    0)
        echo "ALL VERDICTS HOLD: in every pair exactly one user holds their complete set,"
        echo "the other got a 409, and no seat is held by a booking that did not survive."
        exit 0
        ;;
    1)
        echo "VERDICT FAILED - see the FAIL and ORPHAN lines above for the offending seats." >&2
        exit 1
        ;;
    *)
        cannot_verify "the verdict step itself failed (awk exited $verdict_exit)."
        ;;
esac
