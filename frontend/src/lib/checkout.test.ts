import { describe, expect, it } from 'vitest'

import type { BookingResponse, BookingStatus } from '../api/types'
import {
  CONFIRM_ATTEMPTS,
  CONFIRM_RETRY_DELAYS_MS,
  RECHECK_AFTER_LAPSE_MS,
  SWEEP_INTERVAL_MS,
  explainRefusal,
  formatCountdown,
  isAmbiguousFailure,
  remainingMs,
  viewOf,
} from './checkout'

/**
 * What state a booking is in, how long is left, and why a confirm was refused.
 *
 * viewOf IS THE ONE IMPLEMENTATION of "a PENDING booking whose expiry has passed is not
 * really pending". Three screens depend on that rule - the checkout page, the bookings
 * list and the seat map's "held by you" - and all three call this function. The seat map
 * used to carry its own copy; it was folded in when these tests were written, so that what
 * is pinned here is what every screen does.
 *
 * Every test was watched failing against a deliberately broken source; see the commit.
 */

const NOW = Date.parse('2026-10-09T12:00:00.000Z')
const at = (offsetMs: number) => new Date(NOW + offsetMs).toISOString()

function booking(overrides: Partial<BookingResponse> = {}): BookingResponse {
  return {
    id: 12,
    userId: 7,
    showId: 301,
    eventId: 42,
    eventTitle: 'Coldplay - Music of the Spheres',
    venueName: 'Phoenix Arena',
    showStartsAt: at(7 * 24 * 3600_000),
    status: 'PENDING',
    totalAmount: 900,
    expiresAt: at(600_000),
    createdAt: at(0),
    seats: [],
    ...overrides,
  }
}

describe('what state a booking is in', () => {
  it('a PENDING booking with time left is held', () => {
    expect(viewOf(booking({ expiresAt: at(1) }), NOW)).toBe('held')
  })

  it('a PENDING booking is lapsed AT its expiry, not one tick after', () => {
    // The server's own rule is the same way round: confirm refuses a booking whose
    // expiresAt is not strictly after now. A page that still said "held" at the exact
    // instant would offer a Confirm the server is certain to refuse.
    expect(viewOf(booking({ expiresAt: at(0) }), NOW)).toBe('lapsed')
    expect(viewOf(booking({ expiresAt: at(-1) }), NOW)).toBe('lapsed')
  })

  it('a PENDING booking with no expiry at all is lapsed, not held for ever', () => {
    expect(viewOf(booking({ expiresAt: null }), NOW)).toBe('lapsed')
  })

  it.each<[BookingStatus, string]>([
    ['CONFIRMED', 'confirmed'],
    ['CANCELLED', 'cancelled'],
    ['EXPIRED', 'expired'],
  ])('a %s booking is %s whatever its expiry says', (status, expected) => {
    // STATUS IS LOOKED AT FIRST. A cancelled booking keeps its expiresAt, and that can be
    // in the future; a confirmed one has none. Neither is "held" and neither is "lapsed".
    expect(viewOf(booking({ status, expiresAt: at(600_000) }), NOW)).toBe(expected)
    expect(viewOf(booking({ status, expiresAt: at(-600_000) }), NOW)).toBe(expected)
    expect(viewOf(booking({ status, expiresAt: null }), NOW)).toBe(expected)
  })
})

describe('how long is left', () => {
  it('is the distance to the expiry', () => {
    expect(remainingMs(at(90_000), NOW)).toBe(90_000)
  })

  it('is zero once the expiry has passed - never negative', () => {
    expect(remainingMs(at(-5_000), NOW)).toBe(0)
  })

  it('is zero when there is no expiry', () => {
    expect(remainingMs(null, NOW)).toBe(0)
  })
})

describe('the countdown as text', () => {
  it.each([
    [600_000, '10:00'],
    [599_001, '10:00'],
    [599_000, '9:59'],
    [61_000, '1:01'],
    [60_000, '1:00'],
    [9_000, '0:09'],
    [1_000, '0:01'],
    // Rounded UP: one millisecond left is still "0:01". 0:00 is shown only when a click
    // could no longer succeed, never while one still could.
    [1, '0:01'],
    [0, '0:00'],
    [-3_000, '0:00'],
  ])('%i ms reads %s', (ms, text) => {
    expect(formatCountdown(ms)).toBe(text)
  })
})

describe('why a confirm was refused, from the booking and not from the message', () => {
  it('the booking is CONFIRMED: somebody else, or another tab, got there first', () => {
    expect(explainRefusal(booking({ status: 'CONFIRMED', expiresAt: null }), NOW)).toBe('confirmed')
  })

  it('the booking is CANCELLED', () => {
    expect(explainRefusal(booking({ status: 'CANCELLED' }), NOW)).toBe('cancelled')
  })

  it('the booking is EXPIRED', () => {
    expect(explainRefusal(booking({ status: 'EXPIRED', expiresAt: at(-1) }), NOW)).toBe('expired')
  })

  it('still PENDING and past its expiry: the hold ran out', () => {
    expect(explainRefusal(booking({ expiresAt: at(-1) }), NOW)).toBe('lapsed')
  })

  it('still PENDING, hold in date, and the show has started - at the start instant, not after it', () => {
    expect(explainRefusal(booking({ showStartsAt: at(0) }), NOW)).toBe('show-started')
    expect(explainRefusal(booking({ showStartsAt: at(-60_000) }), NOW)).toBe('show-started')
    expect(explainRefusal(booking({ showStartsAt: at(1) }), NOW)).not.toBe('show-started')
  })

  it('a hold that ran out is "lapsed" even if the show has also started: the nearer cause wins', () => {
    expect(explainRefusal(booking({ expiresAt: at(-1), showStartsAt: at(-60_000) }), NOW)).toBe('lapsed')
  })

  it('still PENDING with nothing in the booking to explain it: the two causes it cannot split', () => {
    // "Hold lost" and "seat sold to someone else". The booking is identical in both.
    expect(explainRefusal(booking(), NOW)).toBe('unavailable')
    // A booking held before showStartsAt was stored has no start time to compare.
    expect(explainRefusal(booking({ showStartsAt: null }), NOW)).toBe('unavailable')
  })
})

describe('which confirm failures leave it unknown whether the booking was confirmed', () => {
  it('no response at all', () => {
    expect(isAmbiguousFailure({ kind: 'unreachable', status: undefined })).toBe(true)
  })

  it.each([500, 502, 503, 504])('a %i', (status) => {
    expect(isAmbiguousFailure({ kind: 'http', status })).toBe(true)
  })

  it.each([400, 401, 404, 409, 429, 499])('NOT a %i: the server understood and refused, which is an answer', (status) => {
    expect(isAmbiguousFailure({ kind: 'http', status })).toBe(false)
  })
})

describe('the retry and re-check schedule', () => {
  it('is three attempts with a wait before each retry', () => {
    expect(CONFIRM_ATTEMPTS).toBe(3)
    // One gap fewer than attempts. A fourth attempt with no delay defined would retry
    // instantly, which is the opposite of backing off.
    expect(CONFIRM_RETRY_DELAYS_MS).toHaveLength(CONFIRM_ATTEMPTS - 1)
    expect(CONFIRM_RETRY_DELAYS_MS).toEqual([2_000, 4_000])
  })

  it('looks again after the sweeper has had its turn - strictly after, not at the same moment', () => {
    expect(SWEEP_INTERVAL_MS).toBe(60_000)
    expect(RECHECK_AFTER_LAPSE_MS).toBeGreaterThan(SWEEP_INTERVAL_MS)
  })
})
