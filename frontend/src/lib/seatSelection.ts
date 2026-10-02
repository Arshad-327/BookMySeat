/**
 * What the seat-map page knows that the API does not: which seats the user has picked,
 * which ones a 409 said somebody else is buying, and which Idempotency-Key the next hold
 * will carry.
 *
 * PLAIN FUNCTIONS, NO REACT, and that is the point of the file. Everything here is a pure
 * function from a state and an event to a new state; the clock and the key generator are
 * passed in rather than reached for. This is the one piece of frontend logic where a unit
 * test catches a real bug - drop the conflicting seats and keep the rest, expire a mark
 * after ten minutes, stop at ten seats, change the key when the seat set changes - and it
 * can be tested without rendering anything. seatSelection.test.ts does, and each test
 * there was shown to fail against a deliberately broken copy of this file.
 */

/**
 * The most seats one booking may hold. NOT a UI preference: booking-service's
 * CreateBookingRequest refuses more with a 400 ("seatIds must not exceed 10 seats per
 * booking"). Enforced here so the eleventh click is explained on the spot instead of
 * surfacing as a validation error after the user has finished choosing.
 */
export const MAX_SEATS_PER_BOOKING = 10

/**
 * How long a "being booked by someone else" mark lasts. Ten minutes because that is the
 * hold TTL: a hold that existed when the 409 arrived cannot still exist ten minutes
 * later, so past that the mark would be claiming something that is certainly untrue.
 */
export const TAKEN_MARK_TTL_MS = 10 * 60 * 1000

export interface SelectionState {
  /** show_seats ids the user has picked, in the order they picked them. */
  readonly selected: readonly number[]
  /**
   * Seats a 409 named as held by another booking: seat id -> when the mark was made (ms).
   *
   * A WARNING, NOT A LOCK. The seat stays clickable. If it were locked for the ten
   * minutes, a seat the other user gave up after thirty seconds would stay dead on this
   * screen for nine and a half more - and "cancel frees the seat at once" is one of the
   * things this system is built to show.
   */
  readonly taken: Readonly<Record<number, number>>
  /**
   * The Idempotency-Key the next hold request will send.
   *
   * It names ONE attempt: this exact set of seats. It is replaced whenever the set
   * changes, so a different set of seats can never be answered with the booking an
   * earlier set created. It is deliberately NOT replaced when a request fails without an
   * answer: sending the identical request again with the same key is the one case the key
   * exists for - if the first attempt did land, the retry gets that booking back instead
   * of making a second.
   */
  readonly idempotencyKey: string
}

/** Why a click on a seat did nothing. */
export type ToggleRefusal = 'limit'

export interface ToggleResult {
  state: SelectionState
  refused?: ToggleRefusal
}

export function emptySelection(newKey: () => string): SelectionState {
  return { selected: [], taken: {}, idempotencyKey: newKey() }
}

/**
 * The user clicked a seat that can be picked: select it, or unselect it.
 *
 * Selecting a seat that carries a "taken" mark clears the mark - the user has seen the
 * warning and is trying anyway, which is how they find out the other person let go.
 */
export function toggleSeat(state: SelectionState, seatId: number, newKey: () => string): ToggleResult {
  if (state.selected.includes(seatId)) {
    return {
      state: { ...state, selected: state.selected.filter((id) => id !== seatId), idempotencyKey: newKey() },
    }
  }
  if (state.selected.length >= MAX_SEATS_PER_BOOKING) {
    return { state, refused: 'limit' }
  }
  return {
    state: {
      selected: [...state.selected, seatId],
      taken: without(state.taken, [seatId]),
      idempotencyKey: newKey(),
    },
  }
}

/**
 * A hold came back 409 naming the seats another booking holds.
 *
 * Those seats leave the selection and are marked; EVERYTHING ELSE STAYS SELECTED. The
 * hold is all-or-nothing on the server, so nothing was held - but the user's other
 * choices were fine, and making them pick again from scratch because one seat was
 * contested is the worse experience. The key changes because the seat set has.
 */
export function applyConflict(
  state: SelectionState,
  conflictingSeatIds: readonly number[],
  now: number,
  newKey: () => string,
): SelectionState {
  const conflicting = new Set(conflictingSeatIds)
  const taken: Record<number, number> = { ...state.taken }
  for (const id of conflicting) {
    taken[id] = now
  }
  return {
    selected: state.selected.filter((id) => !conflicting.has(id)),
    taken,
    idempotencyKey: newKey(),
  }
}

export interface ReconcileResult {
  state: SelectionState
  /** Selected seats that had to be dropped, for a notice. Empty almost always. */
  dropped: number[]
}

/**
 * Brings the state back in line with what the server now says. Run after every poll.
 *
 *  - A selected seat that has become SOLD, or that turns out to be one the user already
 *    holds, is dropped from the selection (and reported, so the page can say so).
 *  - A "taken" mark is removed once its seat is sold - the map now shows the truth, and
 *    the mark has nothing left to add - or once it is ten minutes old.
 *
 * Returns the SAME state object when nothing changed, so a caller can skip a re-render.
 */
export function reconcile(
  state: SelectionState,
  unavailable: ReadonlySet<number>,
  now: number,
  newKey: () => string,
): ReconcileResult {
  const dropped = state.selected.filter((id) => unavailable.has(id))
  const staleMarks = Object.keys(state.taken)
    .map(Number)
    .filter((id) => unavailable.has(id) || now - (state.taken[id] ?? 0) >= TAKEN_MARK_TTL_MS)

  if (dropped.length === 0 && staleMarks.length === 0) {
    return { state, dropped }
  }
  return {
    state: {
      selected: dropped.length ? state.selected.filter((id) => !unavailable.has(id)) : state.selected,
      taken: without(state.taken, staleMarks),
      // Only a change to the seat SET changes the key. Expiring a mark is not one.
      idempotencyKey: dropped.length ? newKey() : state.idempotencyKey,
    },
    dropped,
  }
}

/** Whether a seat currently carries a live "taken" mark. */
export function isMarkedTaken(state: SelectionState, seatId: number, now: number): boolean {
  const markedAt = state.taken[seatId]
  return markedAt !== undefined && now - markedAt < TAKEN_MARK_TTL_MS
}

function without(marks: Readonly<Record<number, number>>, seatIds: readonly number[]): Record<number, number> {
  const next: Record<number, number> = { ...marks }
  for (const id of seatIds) {
    delete next[id]
  }
  return next
}
