/*
 * overlapping-seats.js
 *
 * The test that justifies hold_seats.lua being ONE atomic script.
 *
 * ---------------------------------------------------------------------------
 * WHY THIS TEST EXISTS
 * ---------------------------------------------------------------------------
 * single-seat-contention.js proves that concurrent claims on one seat are
 * mutually exclusive. A naive implementation passes it: a loop issuing one
 * SET NX per seat is still exclusive for a single key. What such a loop cannot
 * guarantee is taking SEVERAL seats all or nothing. Two requests that overlap
 * on one seat can each win part of what they asked for:
 *
 *     A wants {X, Y}            B wants {Y, Z}
 *     A: SET X NX -> ok
 *                               B: SET Z NX -> ok
 *     A: SET Y NX -> ok
 *                               B: SET Y NX -> fails
 *
 * B now owns Z for a booking that is about to be refused and rolled back. Unless
 * that partial acquisition is undone - and undone without racing anything else -
 * Z is an orphan: held in Redis by a booking that does not exist, unsellable
 * until its TTL runs out. hold_seats.lua makes acquire-or-undo one indivisible
 * step. Only a test in which requests overlap can show that it does.
 *
 * ---------------------------------------------------------------------------
 * WHAT IT DOES - AND WHAT IT DELIBERATELY DOES NOT
 * ---------------------------------------------------------------------------
 * PAIRS pairs (default 20). In each pair user A holds {X, Y} and user B holds
 * {Y, Z}, on the same show, at the same instant. Seat triples are disjoint across
 * pairs, so a pair can only interfere with itself. Every request is released
 * together through the same barrier, and measured with the same spread, as
 * single-seat-contention.js.
 *
 * It reports what happened OVER HTTP, per pair: each user's status, booking id,
 * and the seat ids named in a 409. It makes no claim about Redis or MySQL and
 * passes no verdict. Grading happens outside, in verify-overlapping.sh, against
 * the stores themselves: a script that grades its own run is weaker evidence than
 * one checked independently.
 *
 * For that handoff, stdout ends with machine-readable lines:
 *     OVERLAP-PLAN    one per pair: the seats, and which user asked for which set
 *     OVERLAP-RESULT  one per request: user, status, booking id, 409 seat ids
 *     OVERLAP-END     present only when the output is complete
 * Save stdout to a file and pass that file to verify-overlapping.sh.
 *
 * Run ./load-tests/reset-fixtures.sh first. It guarantees every seat is AVAILABLE
 * and that zero seat:hold:* keys exist - which this script cannot check, because
 * stock k6 has no Redis client. setup() does check that the show has enough
 * AVAILABLE seats for PAIRS disjoint triples.
 *
 * Confirm is never called, exactly as in single-seat-contention.js.
 */

import http from 'k6/http';
import { sleep } from 'k6';
import { Counter, Gauge, Trend } from 'k6/metrics';

// ---------------------------------------------------------------------------
// Configuration, all from the environment (-e NAME=value)
// ---------------------------------------------------------------------------

const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8083';

// Read once, in setup(), to choose seats that exist in the show and are AVAILABLE.
const EVENT_SERVICE_URL = __ENV.EVENT_SERVICE_URL || 'http://host.docker.internal:8082';

const SHOW_ID = parseInt(__ENV.SHOW_ID || '1', 10);
const PAIRS = parseInt(__ENV.PAIRS || '20', 10);
const VUS = PAIRS * 2;

// Same meaning and defaults as single-seat-contention.js.
const START_DELAY_MS = parseInt(__ENV.START_DELAY_MS || '3000', 10);
const SPIN_MS = parseInt(__ENV.SPIN_MS || '25', 10);
const VERBOSE = (__ENV.VERBOSE || 'false').toLowerCase() === 'true';

// Pair n's user A is USER_ID + 2(n - 1) and user B is the next id: every request
// comes from a distinct user, so a booking in booking_db identifies its request.
const BASE_USER_ID = parseInt(__ENV.USER_ID || '1', 10);

if (!(PAIRS >= 1)) {
  throw new Error(`PAIRS must be a positive integer, got "${__ENV.PAIRS}"`);
}

// ---------------------------------------------------------------------------
// Metrics
// ---------------------------------------------------------------------------

/*
 * Status counters and the spread Trend are the same as in
 * single-seat-contention.js, so the two runs report on identical terms.
 */
const TRACKED_STATUSES = [
  0,
  200, 201, 202, 204,
  400, 401, 403, 404, 405, 408, 409, 412, 422, 425, 429,
  500, 501, 502, 503, 504,
];

const statusCounters = {};
for (const code of TRACKED_STATUSES) {
  statusCounters[code] = new Counter(`status_${code}`);
}
const statusOther = new Counter('status_other');

/** How far after the shared release instant each request actually went out. */
const collisionOffset = new Trend('collision_offset_ms');

/** Latency of the burst requests only; http_req_duration would include setup()'s seat-map read. */
const holdDuration = new Trend('hold_duration_ms', true);

/*
 * Per-request outcomes: one set of metrics per pair and side.
 *
 * Every VU runs in its own JS runtime, so an outcome can only reach
 * handleSummary through a registered metric - and a Gauge keeps only the LAST
 * value any VU wrote, so one Gauge shared across pairs would report whichever
 * request happened to finish last. Hence a Gauge per pair and side for the status
 * and the booking id, and a Counter per requested seat meaning "this seat was
 * named in the 409". All declared up front: metrics can only be created in init
 * context.
 */
const outcomes = {};
for (let pair = 1; pair <= PAIRS; pair++) {
  for (const side of ['A', 'B']) {
    const prefix = `pair_${pad(pair)}_${side}`;
    outcomes[`${pair}${side}`] = {
      status: new Gauge(`${prefix}_status`),
      bookingId: new Gauge(`${prefix}_booking_id`),
      // Index i means "the i-th seat this side requested was named in the 409".
      conflict: [new Counter(`${prefix}_conflict_0`), new Counter(`${prefix}_conflict_1`)],
      // A 409 naming a seat this side never asked for. Should never happen.
      conflictUnrequested: new Counter(`${prefix}_conflict_unrequested`),
    };
  }
}

// ---------------------------------------------------------------------------
// Scenario
// ---------------------------------------------------------------------------

export const options = {
  scenarios: {
    overlapping_seats_burst: {
      // per-vu-iterations, as in single-seat-contention.js: every VU exists
      // before the barrier releases, and each sends exactly one request.
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: 1,
      maxDuration: '2m',
      gracefulStop: '30s',
    },
  },

  // NO THRESHOLDS, DELIBERATELY. This script reports; verify-overlapping.sh judges.

  summaryTrendStats: ['min', 'avg', 'med', 'max', 'p(95)', 'p(99)'],
};

// ---------------------------------------------------------------------------
// Plan and barrier
// ---------------------------------------------------------------------------

export function setup() {
  const seatMapUrl = `${EVENT_SERVICE_URL}/api/shows/${SHOW_ID}/seats`;
  const seatMap = http.get(seatMapUrl, { tags: { name: 'setup: GET seat map' } });
  if (seatMap.status !== 200) {
    throw new Error(`setup: GET ${seatMapUrl} returned ${seatMap.status}. `
      + `Is event-service running, and does show ${SHOW_ID} exist?`);
  }

  // AVAILABLE is event_db's view only. A seat held in Redis still reads AVAILABLE,
  // which is why reset-fixtures.sh, which clears the holds, must run first.
  const available = [];
  for (const row of seatMap.json('rows')) {
    for (const seat of row.seats) {
      if (seat.status === 'AVAILABLE') {
        available.push(seat.id);
      }
    }
  }
  available.sort((a, b) => a - b);

  const needed = PAIRS * 3;
  if (available.length < needed) {
    throw new Error(`setup: show ${SHOW_ID} has ${available.length} AVAILABLE seats, but `
      + `${PAIRS} disjoint pairs need ${needed}. Run ./load-tests/reset-fixtures.sh and try again.`);
  }

  // Disjoint triples: pair n takes the next three AVAILABLE ids, in ascending order.
  const plan = [];
  for (let i = 0; i < PAIRS; i++) {
    const [x, y, z] = available.slice(i * 3, i * 3 + 3);
    plan.push({
      pair: i + 1,
      userA: BASE_USER_ID + i * 2,
      seatsA: [x, y],
      userB: BASE_USER_ID + i * 2 + 1,
      seatsB: [y, z],
    });
  }

  // Taken after the seat-map read, so that read cannot eat into START_DELAY_MS.
  const startAt = Date.now() + START_DELAY_MS;

  console.log('');
  console.log('  overlapping seats');
  console.log(`    target        ${BASE_URL}/api/bookings/hold`);
  console.log(`    show          showId=${SHOW_ID}`);
  console.log(`    pairs         ${PAIRS} (${VUS} VUs, one request each)`);
  console.log(`    seats         ${available[0]}..${available[needed - 1]} in disjoint triples; A wants {X,Y}, B wants {Y,Z}`);
  console.log(`    users         ${BASE_USER_ID}..${BASE_USER_ID + VUS - 1} (distinct)`);
  console.log(`    releasing in  ${START_DELAY_MS}ms, all VUs at once`);
  console.log('');

  return { startAt, plan };
}

/** Identical to single-seat-contention.js: sleep until just before release, then spin. */
function waitForRelease(startAt) {
  const sleepUntil = startAt - SPIN_MS;
  const sleepMs = sleepUntil - Date.now();
  if (sleepMs > 0) {
    sleep(sleepMs / 1000);
  }
  // Final approach. Blocks this VU only, and only for a few milliseconds.
  while (Date.now() < startAt) {
    // spin
  }
}

// ---------------------------------------------------------------------------
// The request
// ---------------------------------------------------------------------------

export default function (data) {
  waitForRelease(data.startAt);

  // VUs 1 and 2 are pair 1 (A, B), VUs 3 and 4 are pair 2, and so on.
  const entry = data.plan[Math.floor((__VU - 1) / 2)];
  const side = __VU % 2 === 1 ? 'A' : 'B';
  const userId = side === 'A' ? entry.userA : entry.userB;
  const seatIds = side === 'A' ? entry.seatsA : entry.seatsB;
  const outcome = outcomes[`${entry.pair}${side}`];

  const headers = {
    'Content-Type': 'application/json',
    'X-User-Id': String(userId),
  };
  const body = JSON.stringify({ showId: SHOW_ID, seatIds });

  const sentOffset = Date.now() - data.startAt;
  const res = http.post(`${BASE_URL}/api/bookings/hold`, body, {
    headers,
    tags: { name: 'POST /api/bookings/hold' },
  });

  collisionOffset.add(sentOffset);
  holdDuration.add(res.timings.duration);

  const status = res.status;
  if (statusCounters[status]) {
    statusCounters[status].add(1);
  } else {
    statusOther.add(1);
    console.warn(`pair ${entry.pair} ${side}: unlisted status ${status} - ${truncate(res.body)}`);
  }
  outcome.status.add(status);

  if (status === 201) {
    const bookingId = jsonField(res, 'id');
    if (Number.isInteger(bookingId)) {
      outcome.bookingId.add(bookingId);
    } else {
      console.warn(`pair ${entry.pair} ${side}: 201 without a numeric booking id - ${truncate(res.body)}`);
    }
  }

  if (status === 409) {
    const named = jsonField(res, 'conflictingSeatIds');
    if (Array.isArray(named)) {
      for (const seatId of named) {
        const index = seatIds.indexOf(seatId);
        if (index >= 0) {
          outcome.conflict[index].add(1);
        } else {
          outcome.conflictUnrequested.add(1);
          console.warn(`pair ${entry.pair} ${side}: 409 named seat ${seatId}, which it did not request`);
        }
      }
    } else {
      // A 409 without the list is a different refusal - a seat already BOOKED,
      // for instance - and says nothing about holds. Surfaced, not guessed at.
      console.warn(`pair ${entry.pair} ${side}: 409 without conflictingSeatIds - ${truncate(res.body)}`);
    }
  }

  // Transport failures carry no HTTP status and are easy to miss, so always surfaced.
  if (status === 0) {
    console.error(`pair ${entry.pair} ${side}: transport failure - ${res.error || 'no error text'} (${res.error_code || 'no code'})`);
  }

  if (VERBOSE) {
    console.log(`pair ${entry.pair} ${side} user ${userId} seats ${seatIds} -> ${status} (+${sentOffset}ms, ${Math.round(res.timings.duration)}ms) ${truncate(res.body)}`);
  }
}

function jsonField(res, field) {
  try {
    return res.json(field);
  } catch (e) {
    return undefined;
  }
}

function truncate(body) {
  if (!body) {
    return '';
  }
  const text = String(body).replace(/\s+/g, ' ');
  return text.length > 160 ? `${text.slice(0, 160)}...` : text;
}

function pad(n) {
  return String(n).padStart(2, '0');
}

// ---------------------------------------------------------------------------
// Summary
// ---------------------------------------------------------------------------

export function handleSummary(data) {
  const count = (name) => {
    const metric = data.metrics[name];
    return metric && metric.values ? (metric.values.count || 0) : 0;
  };
  const trend = (name, stat) => {
    const metric = data.metrics[name];
    return metric && metric.values && metric.values[stat] !== undefined
      ? metric.values[stat]
      : null;
  };
  const gauge = (name) => {
    const metric = data.metrics[name];
    return metric && metric.values && metric.values.value !== undefined
      ? metric.values.value
      : null;
  };

  const plan = data.setup_data && data.setup_data.plan;

  // Burst requests only, counted from the status counters: http_reqs would also
  // include setup()'s seat-map read.
  const tracked = TRACKED_STATUSES.reduce((sum, code) => sum + count(`status_${code}`), 0);
  const other = count('status_other');
  const total = tracked + other;
  const twoXx = TRACKED_STATUSES
    .filter((code) => code >= 200 && code < 300)
    .reduce((sum, code) => sum + count(`status_${code}`), 0);
  const conflicts = count('status_409');
  const seen = TRACKED_STATUSES
    .map((code) => ({ code, n: count(`status_${code}`) }))
    .filter((row) => row.n > 0);

  const lines = [];
  const rule = '  ' + '-'.repeat(62);

  lines.push('');
  lines.push(rule);
  lines.push('  OVERLAPPING SEATS - RAW RESULT (HTTP responses only)');
  lines.push(rule);
  lines.push(`  target                 ${BASE_URL}/api/bookings/hold`);
  lines.push(`  showId                 ${SHOW_ID}`);
  lines.push(`  pairs / vus            ${PAIRS} / ${VUS}`);
  lines.push('');
  lines.push('  REQUESTS');
  lines.push(`    total                ${total}`);
  lines.push(`    2xx                  ${twoXx}`);
  lines.push(`    409                  ${conflicts}`);
  lines.push(`    other / unlisted     ${other}`);
  lines.push('');
  lines.push('  STATUS BREAKDOWN');
  if (seen.length === 0) {
    lines.push('    (no responses recorded)');
  } else {
    for (const row of seen) {
      const label = row.code === 0 ? '0 (transport failure)' : String(row.code);
      lines.push(`    ${label.padEnd(21)}${row.n}`);
    }
  }
  if (other > 0) {
    lines.push(`    unlisted status      ${other}   <- see console.warn lines above`);
  }
  lines.push('');
  lines.push('  BURST TIGHTNESS (how far after the release instant each request went out)');
  const spreadMin = trend('collision_offset_ms', 'min');
  const spreadMax = trend('collision_offset_ms', 'max');
  if (spreadMin === null) {
    lines.push('    (not recorded)');
  } else {
    lines.push(`    min / avg / max      ${fmt(spreadMin)} / ${fmt(trend('collision_offset_ms', 'avg'))} / ${fmt(spreadMax)} ms`);
    lines.push(`    spread               ${fmt(spreadMax - spreadMin)} ms`);
    lines.push('    A wide spread means the VUs did not really collide. Raise START_DELAY_MS.');
  }
  lines.push('');
  lines.push('  LATENCY (hold requests only, ms)');
  lines.push(`    min / avg / med      ${fmt(trend('hold_duration_ms', 'min'))} / ${fmt(trend('hold_duration_ms', 'avg'))} / ${fmt(trend('hold_duration_ms', 'med'))}`);
  lines.push(`    p95 / p99 / max      ${fmt(trend('hold_duration_ms', 'p(95)'))} / ${fmt(trend('hold_duration_ms', 'p(99)'))} / ${fmt(trend('hold_duration_ms', 'max'))}`);
  lines.push('');

  // One record per request, shared by the human table and the machine lines so
  // the two can never disagree.
  const records = [];
  if (plan) {
    for (const entry of plan) {
      for (const side of ['A', 'B']) {
        const prefix = `pair_${pad(entry.pair)}_${side}`;
        const seatIds = side === 'A' ? entry.seatsA : entry.seatsB;
        const status = gauge(`${prefix}_status`);
        const bookingId = gauge(`${prefix}_booking_id`);
        const named = seatIds.filter((seatId, index) => count(`${prefix}_conflict_${index}`) > 0);
        records.push({
          pair: entry.pair,
          side,
          user: side === 'A' ? entry.userA : entry.userB,
          seats: seatIds.join(','),
          status: status === null ? 'none' : String(status),
          booking: bookingId === null ? '-' : String(bookingId),
          conflicting: named.length === 0 ? '-' : named.join(','),
          unrequested: count(`${prefix}_conflict_unrequested`),
        });
      }
    }
  }

  lines.push('  PER PAIR (as reported over HTTP - no claim about Redis or MySQL)');
  if (!plan) {
    lines.push('    (no plan: setup() did not complete - see the error above)');
  } else {
    lines.push(`    ${'pair'.padEnd(6)}${'side'.padEnd(6)}${'user'.padEnd(8)}${'seats'.padEnd(12)}${'status'.padEnd(8)}${'booking'.padEnd(10)}409 named`);
    for (const r of records) {
      const flag = r.unrequested > 0 ? `   (+${r.unrequested} seat(s) not requested)` : '';
      lines.push(`    ${(r.side === 'A' ? pad(r.pair) : '').padEnd(6)}${r.side.padEnd(6)}${String(r.user).padEnd(8)}`
        + `${r.seats.padEnd(12)}${r.status.padEnd(8)}${r.booking.padEnd(10)}${r.conflicting}${flag}`);
    }
  }
  lines.push('');
  lines.push(rule);
  lines.push('  These are HTTP responses only, and confirm was never called. They do not');
  lines.push('  say which bookings survived or who holds which seat. Grade the run from');
  lines.push('  outside, within the ten-minute hold TTL:');
  lines.push('    ./load-tests/verify-overlapping.sh <this stdout, saved to a file>');
  lines.push(rule);
  lines.push('');

  if (plan) {
    lines.push('# Machine-readable plan and HTTP outcomes, for verify-overlapping.sh.');
    for (const entry of plan) {
      lines.push(`OVERLAP-PLAN pair=${entry.pair} show=${SHOW_ID} user_a=${entry.userA} seats_a=${entry.seatsA.join(',')} `
        + `user_b=${entry.userB} seats_b=${entry.seatsB.join(',')}`);
    }
    for (const r of records) {
      lines.push(`OVERLAP-RESULT pair=${r.pair} side=${r.side} user=${r.user} status=${r.status} `
        + `booking=${r.booking} conflicting=${r.conflicting} unrequested=${r.unrequested}`);
    }
    lines.push(`OVERLAP-END pairs=${plan.length}`);
    lines.push('');
  }

  return { stdout: lines.join('\n') };
}

function fmt(value) {
  if (value === null || value === undefined) {
    return '-';
  }
  return (Math.round(value * 100) / 100).toString();
}
