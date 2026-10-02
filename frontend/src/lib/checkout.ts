import type { BookingResponse } from '../api/types'

/**
 * The decisions the checkout page makes, as plain functions of a booking and a clock.
 *
 * NO REACT, like lib/seatSelection and for the same reason: "what state is this booking
 * in", "how long is left" and "why was the confirm refused" are the parts of the page a
 * unit test can pin, and they can be tested without rendering anything. The page reads the
 * clock and calls these; nothing here reaches for Date.now() itself.
 */

/**
 * How often booking-service's ExpiredBookingSweeper runs. A booking whose hold has lapsed
 * keeps saying PENDING until the next sweep, so this is the longest the server can go on
 * reporting a dead hold as live - and it is why the page looks again this long after the
 * countdown reaches zero.
 */
export const SWEEP_INTERVAL_MS = 60_000

/**
 * A little past the sweep interval. Asking at exactly sixty seconds can arrive a moment
 * before the sweep that would have answered it.
 */
export const RECHECK_AFTER_LAPSE_MS = SWEEP_INTERVAL_MS + 5_000

/** A confirm that gets no clear answer is tried this many times in all, with these gaps. */
export const CONFIRM_ATTEMPTS = 3
export const CONFIRM_RETRY_DELAYS_MS: readonly number[] = [2_000, 4_000]

/**
 * What the page shows. Four of these are the API's statuses; `lapsed` is not.
 *
 *   held       PENDING, and expiresAt is still ahead on THIS machine's clock
 *   lapsed     PENDING, and expiresAt has passed on this machine's clock
 *   confirmed  CONFIRMED
 *   cancelled  CANCELLED
 *   expired    EXPIRED - the server has said so, which makes it final
 *
 * `lapsed` IS A GUESS AND `expired` IS A FACT, and the page treats them differently for
 * that reason. A PENDING booking with its expiry in the past is one of two things the page
 * cannot tell apart:
 *
 *   - a hold that really has run out, which the sweeper has not reached yet (it keeps
 *     saying PENDING for up to a minute), or
 *   - a perfectly good hold, seen from a machine whose clock is fast.
 *
 * So `lapsed` withdraws the main Confirm button but leaves a way to try: the server, not
 * this clock, decides. Disabling outright would lock a user with a fast clock out of a
 * hold that is still theirs.
 */
export type BookingView = 'held' | 'lapsed' | 'confirmed' | 'cancelled' | 'expired'

export function viewOf(booking: BookingResponse, now: number): BookingView {
  switch (booking.status) {
    case 'CONFIRMED':
      return 'confirmed'
    case 'CANCELLED':
      return 'cancelled'
    case 'EXPIRED':
      return 'expired'
    case 'PENDING':
      return remainingMs(booking.expiresAt, now) > 0 ? 'held' : 'lapsed'
  }
}

/** Milliseconds until the hold runs out, never negative. Zero when there is no expiry. */
export function remainingMs(expiresAt: string | null, now: number): number {
  if (expiresAt === null) {
    return 0
  }
  return Math.max(new Date(expiresAt).getTime() - now, 0)
}

/**
 * "9:59", "0:07". Rounded UP to the whole second, so the display reaches 0:00 at the
 * moment the hold runs out and not a second before: showing 0:00 while a click would
 * still succeed is the wrong way round.
 */
export function formatCountdown(ms: number): string {
  const seconds = Math.ceil(Math.max(ms, 0) / 1000)
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}

/**
 * Why a confirm came back 409, worked out from the booking as it is NOW - re-read after
 * the refusal - and never from the wording of the error.
 *
 * The API has several reasons to refuse a confirm and tells them apart only in its
 * message. Most can be recovered from the data instead:
 *
 *   confirmed     the booking is CONFIRMED. Another tab, or a double click, got there first
 *   cancelled     it is CANCELLED
 *   expired       it is EXPIRED
 *   lapsed        still PENDING, and its expiry has passed on this clock
 *   show-started  still PENDING, and the show's start time has passed
 *   unavailable   still PENDING, and neither of those. The hold was lost, or a seat was sold
 *                 to someone else - TWO causes this function cannot split, because nothing
 *                 in the booking differs between them. That is the honest limit of deciding
 *                 without a machine-readable error code, and the page says both.
 */
export type ConfirmRefusal = 'confirmed' | 'cancelled' | 'expired' | 'lapsed' | 'show-started' | 'unavailable'

export function explainRefusal(booking: BookingResponse, now: number): ConfirmRefusal {
  const view = viewOf(booking, now)
  if (view !== 'held') {
    return view
  }
  if (booking.showStartsAt !== null && new Date(booking.showStartsAt).getTime() <= now) {
    return 'show-started'
  }
  return 'unavailable'
}

/**
 * Whether a failed confirm leaves it UNKNOWN if the booking was confirmed.
 *
 * True for no response at all and for any 5xx. A confirm marks the seats in event-service
 * before it commits here, and that step can succeed just before the answer is lost - which
 * is exactly why the API words its 503 "could not be confirmed, please retry" and not
 * "nothing happened". Such a failure is retried with the SAME Idempotency-Key and then
 * settled by reading the booking back.
 *
 * False for every 4xx: the server understood and refused, and that is an answer.
 */
export function isAmbiguousFailure(error: { kind: 'unreachable' | 'http'; status: number | undefined }): boolean {
  return error.kind === 'unreachable' || (error.status !== undefined && error.status >= 500)
}
