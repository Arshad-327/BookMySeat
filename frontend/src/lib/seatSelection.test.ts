import { describe, expect, it } from 'vitest'

import {
  MAX_SEATS_PER_BOOKING,
  TAKEN_MARK_TTL_MS,
  applyConflict,
  emptySelection,
  isMarkedTaken,
  reconcile,
  toggleSeat,
  type SelectionState,
} from './seatSelection'

/**
 * The seat-map page's rules, tested as the plain functions they are.
 *
 * EVERY TEST HERE WAS WATCHED FAILING. This logic already worked when the tests were
 * written, so a passing test proved nothing by itself - it might have asserted something
 * the code does not depend on. Each one was therefore checked the other way round: the
 * behaviour it pins was deliberately broken in the source, the suite was run, and the test
 * had to go red. The mutations and what each one reddened are in the commit that added
 * this file. A test that stays green when its subject is broken is decoration.
 *
 * Keys come from a counter, not from crypto.randomUUID(): "the key changed" and "the key
 * did not change" are then exact assertions, and "k3" in a failure message says which
 * call produced it.
 */
function keys(): () => string {
  let n = 0
  return () => `k${++n}`
}

/** A state with these seats selected, built through the real function. */
function selecting(seatIds: number[], newKey: () => string): SelectionState {
  return seatIds.reduce((state, id) => toggleSeat(state, id, newKey).state, emptySelection(newKey))
}

const NOW = 1_800_000_000_000

describe('selecting seats', () => {
  it('starts empty, with a key already made', () => {
    const state = emptySelection(keys())
    expect(state.selected).toEqual([])
    expect(state.taken).toEqual({})
    expect(state.idempotencyKey).toBe('k1')
  })

  it('selects a seat, and keeps the order seats were picked in', () => {
    const state = selecting([24, 7, 25], keys())
    expect(state.selected).toEqual([24, 7, 25])
  })

  it('unselects a seat that is already selected, leaving the others', () => {
    const newKey = keys()
    const { state, refused } = toggleSeat(selecting([24, 7, 25], newKey), 7, newKey)
    expect(state.selected).toEqual([24, 25])
    expect(refused).toBeUndefined()
  })
})

describe('the ten-seat limit', () => {
  it('is ten, because that is what the API accepts', () => {
    // CreateBookingRequest: @Size(max = 10). If the API's limit moves, this is the line
    // that should be changed on purpose rather than discovered as a 400.
    expect(MAX_SEATS_PER_BOOKING).toBe(10)
  })

  it('accepts the tenth seat', () => {
    const newKey = keys()
    const nine = selecting([1, 2, 3, 4, 5, 6, 7, 8, 9], newKey)
    const { state, refused } = toggleSeat(nine, 10, newKey)
    expect(refused).toBeUndefined()
    expect(state.selected).toHaveLength(10)
  })

  it('refuses the eleventh, says why, and changes nothing - not even the key', () => {
    const newKey = keys()
    const ten = selecting([1, 2, 3, 4, 5, 6, 7, 8, 9, 10], newKey)
    const result = toggleSeat(ten, 11, newKey)
    expect(result.refused).toBe('limit')
    // The same object: a refused click is not a new attempt at anything.
    expect(result.state).toBe(ten)
    expect(result.state.selected).not.toContain(11)
  })

  it('still lets a seat be UNselected at the limit, which makes room for another', () => {
    const newKey = keys()
    const ten = selecting([1, 2, 3, 4, 5, 6, 7, 8, 9, 10], newKey)
    const nine = toggleSeat(ten, 4, newKey)
    expect(nine.refused).toBeUndefined()
    expect(nine.state.selected).toHaveLength(9)
    const again = toggleSeat(nine.state, 11, newKey)
    expect(again.refused).toBeUndefined()
    expect(again.state.selected).toContain(11)
  })
})

describe('the idempotency key follows the seat set', () => {
  // The rule with a real double-booking-shaped failure behind it. A key names one attempt
  // at one set of seats. If the set changes and the key does not, the API answers the new
  // request with the booking the OLD set created - the user believes they hold seats they
  // do not. If the key changes when the set has not, a retry of a request that did land
  // makes a second booking.

  it('changes when a seat is added', () => {
    const newKey = keys()
    const before = selecting([24], newKey)
    const after = toggleSeat(before, 25, newKey).state
    expect(after.idempotencyKey).not.toBe(before.idempotencyKey)
  })

  it('changes when a seat is removed', () => {
    const newKey = keys()
    const before = selecting([24, 25], newKey)
    const after = toggleSeat(before, 25, newKey).state
    expect(after.idempotencyKey).not.toBe(before.idempotencyKey)
  })

  it('changes when a conflict takes seats out of the selection', () => {
    const newKey = keys()
    const before = selecting([24, 25], newKey)
    const after = applyConflict(before, [24], NOW, newKey)
    expect(after.idempotencyKey).not.toBe(before.idempotencyKey)
  })

  it('changes when a poll drops a selected seat that has been sold', () => {
    const newKey = keys()
    const before = selecting([24, 25], newKey)
    const after = reconcile(before, new Set([24]), NOW, newKey).state
    expect(after.idempotencyKey).not.toBe(before.idempotencyKey)
  })

  it('does NOT change when a poll changes nothing - so a retry of the same seats sends the same key', () => {
    const newKey = keys()
    const before = selecting([24, 25], newKey)
    // What happens between a failed hold and "Try again": polls arrive, nothing is sold.
    const afterOnePoll = reconcile(before, new Set(), NOW, newKey).state
    const afterTwoPolls = reconcile(afterOnePoll, new Set([99]), NOW + 5_000, newKey).state
    expect(afterTwoPolls.idempotencyKey).toBe(before.idempotencyKey)
  })

  it('does NOT change when only a "taken" mark expires or is cleared - the seat set is the same', () => {
    const newKey = keys()
    const marked = applyConflict(selecting([24, 25], newKey), [24], NOW, newKey)
    // The mark on 24 runs out; 25 is still the whole selection.
    const later = reconcile(marked, new Set(), NOW + TAKEN_MARK_TTL_MS, newKey)
    expect(later.state.taken).toEqual({})
    expect(later.state.selected).toEqual([25])
    expect(later.state.idempotencyKey).toBe(marked.idempotencyKey)
  })
})

describe('a 409 that names the seats somebody else holds', () => {
  it('drops exactly those seats and keeps every other one, in order', () => {
    const newKey = keys()
    const after = applyConflict(selecting([21, 24, 22, 25, 23], newKey), [24, 25], NOW, newKey)
    expect(after.selected).toEqual([21, 22, 23])
  })

  it('marks each conflicting seat as taken, from the moment of the conflict', () => {
    const newKey = keys()
    const after = applyConflict(selecting([24, 25], newKey), [24], NOW, newKey)
    expect(after.taken).toEqual({ 24: NOW })
    expect(isMarkedTaken(after, 24, NOW)).toBe(true)
    expect(isMarkedTaken(after, 25, NOW)).toBe(false)
  })

  it('keeps marks from an earlier conflict', () => {
    const newKey = keys()
    const first = applyConflict(selecting([24, 25], newKey), [24], NOW, newKey)
    const second = applyConflict(first, [25], NOW + 1_000, newKey)
    expect(second.taken).toEqual({ 24: NOW, 25: NOW + 1_000 })
    expect(second.selected).toEqual([])
  })
})

describe('the "being booked by someone else" mark', () => {
  it('lasts ten minutes, which is the hold TTL', () => {
    expect(TAKEN_MARK_TTL_MS).toBe(10 * 60 * 1000)
  })

  it('is live until the last millisecond before ten minutes, and gone AT ten minutes', () => {
    const newKey = keys()
    const marked = applyConflict(selecting([24], newKey), [24], NOW, newKey)
    expect(isMarkedTaken(marked, 24, NOW + TAKEN_MARK_TTL_MS - 1)).toBe(true)
    expect(isMarkedTaken(marked, 24, NOW + TAKEN_MARK_TTL_MS)).toBe(false)
  })

  it('is a warning, not a lock: clicking the seat selects it and clears the mark', () => {
    const newKey = keys()
    const marked = applyConflict(selecting([24, 25], newKey), [24], NOW, newKey)
    const { state, refused } = toggleSeat(marked, 24, newKey)
    expect(refused).toBeUndefined()
    expect(state.selected).toEqual([25, 24])
    expect(state.taken).toEqual({})
  })

  it('is left alone when a different seat is clicked', () => {
    const newKey = keys()
    const marked = applyConflict(selecting([24, 25], newKey), [24], NOW, newKey)
    const { state } = toggleSeat(marked, 30, newKey)
    expect(state.taken).toEqual({ 24: NOW })
  })

  it('is removed by a poll once ten minutes have passed, and not a millisecond before', () => {
    const newKey = keys()
    const marked = applyConflict(selecting([24], newKey), [24], NOW, newKey)
    const early = reconcile(marked, new Set(), NOW + TAKEN_MARK_TTL_MS - 1, newKey)
    expect(early.state.taken).toEqual({ 24: NOW })
    const onTime = reconcile(marked, new Set(), NOW + TAKEN_MARK_TTL_MS, newKey)
    expect(onTime.state.taken).toEqual({})
  })

  it('is removed by a poll as soon as the seat is sold - the map now says so itself', () => {
    const newKey = keys()
    const marked = applyConflict(selecting([24], newKey), [24], NOW, newKey)
    const after = reconcile(marked, new Set([24]), NOW + 1_000, newKey)
    expect(after.state.taken).toEqual({})
  })
})

describe('bringing the selection back in line after a poll', () => {
  it('returns the very same state when there is nothing to do', () => {
    const newKey = keys()
    const state = selecting([24, 25], newKey)
    const result = reconcile(state, new Set([1, 2, 3]), NOW, newKey)
    expect(result.state).toBe(state)
    expect(result.dropped).toEqual([])
  })

  it('drops a selected seat that is no longer available, reports it, and keeps the rest', () => {
    const newKey = keys()
    const result = reconcile(selecting([24, 25, 26], newKey), new Set([25]), NOW, newKey)
    expect(result.dropped).toEqual([25])
    expect(result.state.selected).toEqual([24, 26])
  })
})
