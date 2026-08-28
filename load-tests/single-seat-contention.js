/*
 * single-seat-contention.js
 *
 * Fires N virtual users at ONE seat at the SAME instant and reports what came
 * back. Nothing here asserts, and there are deliberately no thresholds: the
 * script is an instrument, not a test. It reports; you judge.
 *
 * Run it via the grafana/k6 Docker image - see README.md in this directory.
 *
 * ---------------------------------------------------------------------------
 * WHAT IT MEASURES
 * ---------------------------------------------------------------------------
 * Every VU sends exactly one POST /api/bookings/hold for the same showId +
 * seatId. The script counts every HTTP status returned and prints the breakdown.
 *
 * It measures ONE thing: how many callers walk away believing they exclusively
 * own that seat. It deliberately does NOT call POST /api/bookings/{id}/confirm.
 * Confirm adds a second round trip, event-service's seat write and the
 * optimistic lock - all of which have their own failure modes and latency, and
 * folding them in would make any change in the number impossible to attribute
 * to the concurrency fix. See the methodology section of
 * docs/load-test-results.md for the full argument.
 *
 * The HTTP responses alone are not the result. The authoritative check is the
 * verification query in README.md, run afterwards against booking_db - plus, for
 * this endpoint, a look at the seat:hold:{showId}:{seatId} key in Redis to see
 * who actually owns the seat. Read the status counts here, then go and look.
 *
 * NOTE FOR ANYONE COMPARING RUNS: the baseline in docs/load-test-results.md
 * measured POST /api/bookings, which no longer exists - the naive single call
 * was split into hold and confirm. The endpoint changed between runs. What is
 * being compared is "how many users obtained exclusive ownership of one seat",
 * which is well defined either way: in the naive system that claim was a
 * booking, here it is a hold. Everything else about the run is unchanged.
 *
 * ---------------------------------------------------------------------------
 * COLLIDE, DO NOT QUEUE
 * ---------------------------------------------------------------------------
 * A ramp or a plain arrival rate spreads requests over time, and a race that
 * needs simultaneity may simply not occur. So this uses a release barrier:
 * every VU is started and warmed first, then all of them block until one shared
 * wall-clock instant computed in setup() before firing.
 *
 * The tail of that wait is a busy spin rather than a sleep, because sleep()
 * resolution is milliseconds at best and the whole point is to land inside the
 * same few milliseconds. How tight the burst actually was is measured, not
 * assumed - see the collision_offset_ms line in the summary. If that spread is
 * wide, the VUs did not really collide and the run tells you little; raise
 * START_DELAY_MS and try again.
 */

import http from 'k6/http';
import { sleep } from 'k6';
import encoding from 'k6/encoding';
import { Counter, Trend } from 'k6/metrics';

// ---------------------------------------------------------------------------
// Configuration, all from the environment (-e NAME=value)
// ---------------------------------------------------------------------------

const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8083';
const SHOW_ID = __ENV.SHOW_ID || '1';
const SEAT_ID = __ENV.SEAT_ID || '1';
const JWT = __ENV.JWT || '';

const VUS = parseInt(__ENV.VUS || '50', 10);

// How long to wait after setup() before the barrier releases. Must comfortably
// exceed the time k6 needs to spawn every VU, or the earliest VUs fire while
// the last ones are still starting.
const START_DELAY_MS = parseInt(__ENV.START_DELAY_MS || '3000', 10);

// Spin, rather than sleep, for this final stretch before the release instant.
const SPIN_MS = parseInt(__ENV.SPIN_MS || '25', 10);

// One line per VU showing its status. Off by default; the summary is usually enough.
const VERBOSE = (__ENV.VERBOSE || 'false').toLowerCase() === 'true';

/*
 * X-User-Id.
 *
 * booking-service takes the caller's identity from the X-User-Id header and
 * does not read the JWT at all - there is no security filter in that service
 * yet. The JWT is still sent as a Bearer token so this script keeps working
 * unchanged once api-gateway lands and starts validating it.
 *
 * By default each VU books as a DIFFERENT user id, because 50 distinct people
 * racing for one seat is the scenario being reproduced. Set DISTINCT_USERS to
 * false to make every request come from the same user instead.
 */
const DISTINCT_USERS = (__ENV.DISTINCT_USERS || 'true').toLowerCase() !== 'false';
const BASE_USER_ID = parseInt(__ENV.USER_ID || String(subjectFromJwt(JWT) || 1), 10);

/** Reads the `sub` claim out of a JWT payload without verifying the signature. */
function subjectFromJwt(token) {
  if (!token || token.split('.').length !== 3) {
    return null;
  }
  try {
    // JWT payloads are base64url with no padding.
    const payload = JSON.parse(encoding.b64decode(token.split('.')[1], 'rawurl', 's'));
    const sub = parseInt(payload.sub, 10);
    return Number.isNaN(sub) ? null : sub;
  } catch (e) {
    return null;
  }
}

// ---------------------------------------------------------------------------
// Metrics
// ---------------------------------------------------------------------------

/*
 * One Counter per status code.
 *
 * k6 gives every VU its own JS runtime, so a plain object cannot accumulate
 * across VUs - only registered metrics aggregate. Metrics must also be created
 * in init context, which rules out creating them on demand as codes appear. So
 * the codes worth naming are declared up front and anything else lands in
 * status_other, which the summary flags loudly rather than hiding.
 *
 * Status 0 is not an HTTP code: k6 reports it for a transport failure -
 * connection refused, timeout, reset.
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

// ---------------------------------------------------------------------------
// Scenario
// ---------------------------------------------------------------------------

export const options = {
  scenarios: {
    single_seat_burst: {
      /*
       * per-vu-iterations, not constant-arrival-rate: it pre-allocates every VU
       * and gives each exactly one iteration, so all VUs exist before the
       * barrier releases. An arrival rate would meter requests out over the
       * window, which is the queueing this script exists to avoid.
       */
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: 1,
      maxDuration: '2m',
      gracefulStop: '30s',
    },
  },

  /*
   * NO THRESHOLDS, DELIBERATELY.
   *
   * A threshold turns this into a pass/fail gate and makes k6 exit non-zero,
   * which is the opposite of what is wanted: the raw distribution is the
   * result. A 409 here is not a failure and a 201 is not a success - what any
   * of it means depends on the row counts in booking_db afterwards.
   */

  summaryTrendStats: ['min', 'avg', 'med', 'max', 'p(95)', 'p(99)'],
};

// ---------------------------------------------------------------------------
// Barrier
// ---------------------------------------------------------------------------

export function setup() {
  const startAt = Date.now() + START_DELAY_MS;

  console.log('');
  console.log('  single-seat contention');
  console.log(`    target        ${BASE_URL}/api/bookings/hold`);
  console.log(`    show / seat   showId=${SHOW_ID}  seatId=${SEAT_ID}`);
  console.log(`    vus           ${VUS} (one request each)`);
  console.log(`    users         ${DISTINCT_USERS
    ? `${BASE_USER_ID}..${BASE_USER_ID + VUS - 1} (distinct)`
    : `${BASE_USER_ID} (same for every request)`}`);
  console.log(`    jwt           ${JWT ? 'sent as Bearer token (booking-service ignores it today)' : 'NOT SET - no Authorization header will be sent'}`);
  console.log(`    releasing in  ${START_DELAY_MS}ms, all VUs at once`);
  console.log('');

  return { startAt };
}

/** Sleeps until just before the release instant, then spins to land tightly on it. */
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

  const userId = DISTINCT_USERS ? BASE_USER_ID + (__VU - 1) : BASE_USER_ID;

  const headers = {
    'Content-Type': 'application/json',
    'X-User-Id': String(userId),
  };
  if (JWT) {
    headers.Authorization = `Bearer ${JWT}`;
  }

  const body = JSON.stringify({
    showId: parseInt(SHOW_ID, 10),
    seatIds: [parseInt(SEAT_ID, 10)],
  });

  const sentOffset = Date.now() - data.startAt;
  const res = http.post(`${BASE_URL}/api/bookings/hold`, body, {
    headers,
    tags: { name: 'POST /api/bookings/hold' },
  });

  collisionOffset.add(sentOffset);

  const status = res.status;
  if (statusCounters[status]) {
    statusCounters[status].add(1);
  } else {
    statusOther.add(1);
    console.warn(`VU ${__VU}: unlisted status ${status} - ${truncate(res.body)}`);
  }

  // Transport failures carry no HTTP status and are easy to miss in a summary
  // that only shows counts, so they are always surfaced.
  if (status === 0) {
    console.error(`VU ${__VU}: transport failure - ${res.error || 'no error text'} (${res.error_code || 'no code'})`);
  }

  if (VERBOSE) {
    console.log(`VU ${__VU} user ${userId} -> ${status} (+${sentOffset}ms, ${Math.round(res.timings.duration)}ms) ${truncate(res.body)}`);
  }
}

function truncate(body) {
  if (!body) {
    return '';
  }
  const text = String(body).replace(/\s+/g, ' ');
  return text.length > 160 ? `${text.slice(0, 160)}...` : text;
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

  const total = count('http_reqs');

  const twoXx = TRACKED_STATUSES
    .filter((code) => code >= 200 && code < 300)
    .reduce((sum, code) => sum + count(`status_${code}`), 0);

  const conflicts = count('status_409');
  const other = count('status_other');

  const seen = TRACKED_STATUSES
    .map((code) => ({ code, n: count(`status_${code}`) }))
    .filter((row) => row.n > 0);

  const lines = [];
  const rule = '  ' + '-'.repeat(62);

  lines.push('');
  lines.push(rule);
  lines.push('  SINGLE-SEAT CONTENTION - RAW RESULT');
  lines.push(rule);
  lines.push(`  target                 ${BASE_URL}/api/bookings/hold`);
  lines.push(`  showId / seatId        ${SHOW_ID} / ${SEAT_ID}`);
  lines.push(`  vus                    ${VUS}`);
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
  lines.push('  LATENCY (http_req_duration, ms)');
  lines.push(`    min / avg / med      ${fmt(trend('http_req_duration', 'min'))} / ${fmt(trend('http_req_duration', 'avg'))} / ${fmt(trend('http_req_duration', 'med'))}`);
  lines.push(`    p95 / p99 / max      ${fmt(trend('http_req_duration', 'p(95)'))} / ${fmt(trend('http_req_duration', 'p(99)'))} / ${fmt(trend('http_req_duration', 'max'))}`);
  lines.push('');
  lines.push(rule);
  lines.push('  These counts are HTTP responses only, and confirm was never called.');
  lines.push('  A 201 here means the caller was told it owns the seat; whether exactly');
  lines.push('  one caller was told that is a question for the data, not this summary.');
  lines.push('  Run the verification query in load-tests/README.md against booking_db,');
  lines.push('  and check the seat:hold key in Redis, to find out.');
  lines.push(rule);
  lines.push('');

  return { stdout: lines.join('\n') };
}

function fmt(value) {
  if (value === null || value === undefined) {
    return '-';
  }
  return (Math.round(value * 100) / 100).toString();
}
