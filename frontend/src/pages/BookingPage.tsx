import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'

import { cancelBooking, confirmBooking, getBooking } from '../api/bookings'
import { toApiError, type ApiError } from '../api/errors'
import type { BookingResponse } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { ErrorNotice } from '../components/ErrorNotice'
import {
  CONFIRM_ATTEMPTS,
  CONFIRM_RETRY_DELAYS_MS,
  RECHECK_AFTER_LAPSE_MS,
  explainRefusal,
  formatCountdown,
  isAmbiguousFailure,
  remainingMs,
  viewOf,
  type ConfirmRefusal,
} from '../lib/checkout'
import { formatClockTime, formatPrice, formatShowTime, joinList, seatLabel } from '../lib/format'
import { parseId } from '../lib/routeParams'

const sleep = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms))

/** What became of a confirm that never got a clear answer, once the booking was read back. */
type UnclearOutcome =
  /** Read back as still PENDING: nothing was confirmed, and the seats are still held. */
  | 'not-confirmed'
  /** The read-back failed too. The page does not know, and says so. */
  | 'unknown'

/**
 * Checkout: a held booking, its countdown, Confirm and Cancel, and what each ends in.
 *
 * =======================================================================================
 * THE COUNTDOWN IS ADVISORY. THE SERVER IS THE AUTHORITY.
 * =======================================================================================
 * It counts this machine's clock down to a time the server chose. The two can disagree -
 * by seconds from ordinary skew, by hours if the clock is wrong - so nothing the countdown
 * says is final:
 *
 *  - WHILE IT RUNS, Confirm is offered and the server may still refuse it.
 *  - AT ZERO the main Confirm button is withdrawn and the page says the hold APPEARS to
 *    have expired. It then asks the server twice: at once, and again after the sweeper's
 *    interval, because a lapsed hold keeps reading PENDING until the sweeper marks it
 *    EXPIRED and one question at zero would almost always be answered "still PENDING".
 *  - AS LONG AS THE SERVER STILL SAYS PENDING, "Try to confirm anyway" stays. The page
 *    cannot tell a dead hold the sweeper has not reached from a live one seen through a
 *    fast clock (see BookingView in lib/checkout). Only the server can, so the user is
 *    allowed to ask it.
 *  - EXPIRED from the server is final.
 *
 * THERE IS NO RUNNING POLL. Nothing on this page changes unless the user acts, the hold
 * runs out, or another tab acts - so it re-reads on those three: after its own requests,
 * at zero and once more after the sweep interval, and on window focus.
 *
 * =======================================================================================
 * CONFIRM
 * =======================================================================================
 * THE IDEMPOTENCY-KEY is made once, when this page mounts for this booking, and every
 * confirm from the page carries it - the automatic retries and "Try again" alike. It is
 * never regenerated here: there is no seat set to change. It is NOT the hold's key; the
 * API keeps hold keys and confirm keys in separate namespaces, and this page never sees
 * the hold's. A reload makes a new one, which is safe: confirming an already-confirmed
 * booking under a new key is a 409, and a 409 is answered by reading the booking back.
 *
 * A CONFIRM THAT GETS NO CLEAR ANSWER - no response, or a 5xx - may or may not have marked
 * the seats. It is retried with the same key, three attempts in all, and then the booking
 * is read back to find out what is true. The user is told at each step; see `attempt`.
 *
 * A 409 IS NEVER READ FOR ITS WORDING. The booking is read back and the reason is worked
 * out from its status and its two timestamps (explainRefusal). One case cannot be split
 * that way - "hold lost" and "seat sold to someone else" - and the page says both.
 */
export function BookingPage() {
  const id = parseId(useParams().id)
  const { auth } = useAuth()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const userId = auth.status === 'signedIn' ? auth.user.id : null
  const email = auth.status === 'signedIn' ? auth.user.email : null

  const queryKey = ['bookings', 'one', userId, id]
  const booking = useQuery({
    queryKey,
    queryFn: () => getBooking(id as number),
    enabled: id !== null && userId !== null,
    staleTime: 0,
  })

  const data = booking.data
  const now = useNow(data?.status === 'PENDING')
  const view = data ? viewOf(data, now) : null

  // --- The hold running out ------------------------------------------------------------
  const sawHeld = useRef(false)
  const recheckScheduled = useRef(false)
  const refetch = booking.refetch
  useEffect(() => {
    if (view === 'held') {
      sawHeld.current = true
    }
    if (view !== 'lapsed') {
      return
    }
    // Reached zero while the page was open: ask now. (Loaded already lapsed: it has only
    // just been asked.)
    if (sawHeld.current) {
      sawHeld.current = false
      void refetch()
    }
    // And once more when the sweeper has had its turn. Once per page, not per render.
    if (!recheckScheduled.current) {
      recheckScheduled.current = true
      const timer = setTimeout(() => void refetch(), RECHECK_AFTER_LAPSE_MS)
      return () => {
        clearTimeout(timer)
        recheckScheduled.current = false
      }
    }
  }, [view, refetch])

  // --- Confirm -------------------------------------------------------------------------
  const confirmKey = useRef(crypto.randomUUID())
  /** 0 when no confirm is running; otherwise which attempt, 1 to CONFIRM_ATTEMPTS. */
  const [attempt, setAttempt] = useState(0)
  const [refusal, setRefusal] = useState<ConfirmRefusal | null>(null)
  const [unclear, setUnclear] = useState<UnclearOutcome | null>(null)
  const [confirmError, setConfirmError] = useState<ApiError | null>(null)
  /** True once THIS page has confirmed the booking - which is when the email line is true. */
  const [confirmedHere, setConfirmedHere] = useState(false)

  function accept(confirmed: BookingResponse) {
    queryClient.setQueryData(queryKey, confirmed)
    setConfirmedHere(true)
    // The header's count is unchanged by a confirm, but the seat map's "held by you" and
    // the My Bookings tabs are not.
    void queryClient.invalidateQueries({ queryKey: ['bookings', 'pending'] })
    void queryClient.invalidateQueries({ queryKey: ['bookings', 'list'] })
  }

  async function confirm() {
    if (id === null) {
      return
    }
    setRefusal(null)
    setUnclear(null)
    setConfirmError(null)
    try {
      for (let current = 1; current <= CONFIRM_ATTEMPTS; current++) {
        setAttempt(current)
        try {
          accept(await confirmBooking(id, confirmKey.current))
          return
        } catch (caught) {
          const error = toApiError(caught)

          if (isAmbiguousFailure(error)) {
            if (current < CONFIRM_ATTEMPTS) {
              await sleep(CONFIRM_RETRY_DELAYS_MS[current - 1] ?? 0)
              continue
            }
            // Out of attempts and still no clear answer. Stop guessing: read it back.
            try {
              const actual = await getBooking(id)
              queryClient.setQueryData(queryKey, actual)
              if (actual.status === 'CONFIRMED') {
                accept(actual)
              } else {
                setUnclear('not-confirmed')
              }
            } catch {
              setUnclear('unknown')
            }
            return
          }

          if (error.status === 409) {
            // The reason is in the booking, not in the sentence. The sentence is for
            // whoever has the console open - it may carry seat ids.
            console.info('Confirm refused (409):', error.message)
            try {
              const actual = await getBooking(id)
              queryClient.setQueryData(queryKey, actual)
              setRefusal(explainRefusal(actual, Date.now()))
            } catch {
              setRefusal('unavailable')
            }
            return
          }

          // A 429, or a 4xx that is not about this booking's state. Not retried by
          // itself: the notice has a button, and for a 429 it waits out the Retry-After.
          setConfirmError(error)
          return
        }
      }
    } finally {
      setAttempt(0)
    }
  }

  // --- Cancel --------------------------------------------------------------------------
  const [cancelStep, setCancelStep] = useState<'idle' | 'asking' | 'cancelling'>('idle')
  const [cancelError, setCancelError] = useState<ApiError | null>(null)

  async function cancel() {
    if (id === null || !data) {
      return
    }
    setCancelStep('cancelling')
    setCancelError(null)
    try {
      const cancelled = await cancelBooking(id)
      queryClient.setQueryData(queryKey, cancelled)
      // BEFORE navigating: take this booking out of the cached list the seat map draws
      // "held by you" from. Otherwise the map opens with the seats still marked as the
      // user's for as long as the re-read takes - a flash of something no longer true,
      // on the one screen whose job is to show the seats are free again.
      queryClient.setQueryData<BookingResponse[]>(['bookings', 'pending', userId], (held) =>
        held?.filter((other) => other.id !== id),
      )
      void queryClient.invalidateQueries({ queryKey: ['bookings', 'count'] })
      void queryClient.invalidateQueries({ queryKey: ['bookings', 'list'] })
      navigate(`/shows/${cancelled.showId}`, { state: { cancelledBookingId: cancelled.id } })
    } catch (caught) {
      const error = toApiError(caught)
      setCancelStep('idle')
      if (error.status === 409) {
        // Not PENDING any more - confirmed or expired meanwhile. Show what it is now.
        void refetch()
        return
      }
      // Includes a 503: cancel releases the seats in event-service inside its transaction,
      // so with event-service down the booking stays PENDING and can be cancelled again.
      setCancelError(error)
    }
  }

  // --- Render --------------------------------------------------------------------------
  const error = booking.isError ? toApiError(booking.error) : null
  const notFound = id === null || (error?.status === 404 && !data)
  const busy = attempt > 0 || cancelStep === 'cancelling'

  return (
    <main className="mx-auto max-w-2xl px-4 py-8">
      {auth.status === 'restoring' && <p className="text-slate-500">Checking session…</p>}

      {(auth.status === 'signedOut' || auth.status === 'unknown') && (
        <div className="rounded-md border border-slate-200 bg-white p-6">
          <h1 className="text-xl font-semibold text-slate-900">Log in to see this booking</h1>
          <p className="mt-1 text-sm text-slate-600">A booking can only be seen by the account that made it.</p>
          <Link to="/login" className="mt-3 inline-block text-sm font-medium text-slate-900 underline">
            Log in
          </Link>
        </div>
      )}

      {userId !== null && notFound && (
        <div data-testid="booking-not-found" className="rounded-md border border-slate-200 bg-white p-6">
          <h1 className="text-xl font-semibold text-slate-900">Booking not found</h1>
          <p className="mt-1 text-sm text-slate-600">There is no booking of yours at this address.</p>
        </div>
      )}

      {userId !== null && !notFound && !data && booking.isPending && <p className="text-slate-500">Loading booking…</p>}

      {userId !== null && !notFound && !data && error && (
        <ErrorNotice action="load this booking" error={error} onRetry={() => void refetch()} />
      )}

      {data && view && (
        <article data-testid="booking" data-view={view} className="rounded-lg border border-slate-200 bg-white p-6">
          <h1 className="text-2xl font-semibold text-slate-900">
            Booking {data.id} {HEADLINES[view]}
          </h1>

          {view === 'held' && (
            <p className="mt-2 text-slate-700">
              <span
                role="timer"
                aria-live="off"
                data-testid="countdown"
                className={`text-3xl font-semibold tabular-nums ${
                  remainingMs(data.expiresAt, now) <= 60_000 ? 'text-red-700' : 'text-slate-900'
                }`}
              >
                {formatCountdown(remainingMs(data.expiresAt, now))}
              </span>{' '}
              left to confirm. Your seats are held until {data.expiresAt && formatClockTime(data.expiresAt)}.
            </p>
          )}

          {view === 'lapsed' && (
            <p data-testid="lapsed" className="mt-2 rounded-md border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">
              Your hold appears to have expired{data.expiresAt && ` at ${formatClockTime(data.expiresAt)}`}. If it has,
              the seats have been released and can be chosen again. This page is checking with the server.
            </p>
          )}

          {view === 'confirmed' && (
            <div data-testid="confirmed" className="mt-2 text-slate-700">
              <p>Your seats are booked.</p>
              {confirmedHere && email && (
                <p data-testid="email-line" className="mt-1">
                  A confirmation email is on its way to <span className="font-medium text-slate-900">{email}</span>.
                </p>
              )}
              {/* Development builds only. Vite replaces import.meta.env.DEV with a literal,
                  so in a production build this is `false && ...` and the bundler drops the
                  whole branch, text included. Checked by searching the built files for
                  "MailHog", not assumed. */}
              {confirmedHere && import.meta.env.DEV && (
                <p data-testid="mailhog-line" className="mt-1 text-sm text-slate-500">
                  In development, mail is caught by MailHog:{' '}
                  <a href="http://localhost:8025" target="_blank" rel="noreferrer" className="underline">
                    http://localhost:8025
                  </a>
                </p>
              )}
            </div>
          )}

          {view === 'cancelled' && <p className="mt-2 text-slate-700">Its seats were released.</p>}

          {view === 'expired' && (
            <p data-testid="expired" className="mt-2 text-slate-700">
              It was not confirmed in time, and its seats were released.
            </p>
          )}

          <Details booking={data} />

          {/* One polite region for everything that happens without the user looking: the
              last minute, each attempt, and how a confirm ended. The countdown itself is
              aria-live="off" - sixty announcements a minute would be unusable. */}
          <div aria-live="polite" className="mt-6 space-y-3">
            {view === 'held' && remainingMs(data.expiresAt, now) <= 60_000 && (
              <p className="sr-only">Less than one minute left to confirm.</p>
            )}

            {attempt > 1 && (
              <p data-testid="confirm-attempt" className="rounded-md border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">
                The booking service did not answer. Trying again ({attempt} of {CONFIRM_ATTEMPTS})…
              </p>
            )}

            {unclear === 'not-confirmed' && view !== 'confirmed' && (
              <p data-testid="confirm-unclear" className="rounded-md border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">
                We could not confirm this booking. Nothing has been confirmed
                {view === 'held' && data.expiresAt
                  ? `, and your seats are still held until ${formatClockTime(data.expiresAt)}.`
                  : '.'}{' '}
                You can try again.
              </p>
            )}

            {unclear === 'unknown' && (
              <p data-testid="confirm-unclear" className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-900">
                We could not find out whether this booking was confirmed. Look for it under{' '}
                <Link to="/bookings?tab=tickets" className="font-medium underline">
                  My bookings
                </Link>{' '}
                in a moment before trying again.
              </p>
            )}

            {refusal && REFUSALS[refusal] && view !== 'confirmed' && (
              <p data-testid="confirm-refused" className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-900">
                {REFUSALS[refusal]}
              </p>
            )}

            {confirmError && <ErrorNotice action="confirm this booking" error={confirmError} onRetry={() => void confirm()} />}
            {cancelError && <ErrorNotice action="cancel this booking" error={cancelError} onRetry={() => void cancel()} />}
          </div>

          {/* Actions. Only a PENDING booking has any. */}
          {(view === 'held' || view === 'lapsed') && !confirmError && !cancelError && (
            <div className="mt-6 flex flex-wrap items-center gap-3">
              {view === 'held' && cancelStep !== 'asking' && (
                <button
                  type="button"
                  data-testid="confirm-button"
                  onClick={() => void confirm()}
                  disabled={busy}
                  className="rounded bg-slate-900 px-4 py-2 font-medium text-white disabled:cursor-not-allowed disabled:opacity-50"
                >
                  {attempt > 0 ? 'Confirming…' : unclear === 'not-confirmed' ? 'Try again' : 'Confirm booking'}
                </button>
              )}

              {/* The countdown says the hold is gone; only the server knows. See the top
                  of this file for why this button exists instead of a disabled one. */}
              {view === 'lapsed' && (
                <button
                  type="button"
                  data-testid="confirm-anyway-button"
                  onClick={() => void confirm()}
                  disabled={busy}
                  className="rounded border border-slate-400 bg-white px-4 py-2 font-medium text-slate-900 disabled:cursor-not-allowed disabled:opacity-50"
                >
                  {attempt > 0 ? 'Confirming…' : 'Try to confirm anyway'}
                </button>
              )}

              {/* Two steps, inline. One click should not throw away a held seat, and a
                  modal is a component library this project does not have. */}
              {view === 'held' && cancelStep === 'idle' && (
                <button
                  type="button"
                  data-testid="cancel-button"
                  onClick={() => setCancelStep('asking')}
                  disabled={busy}
                  className="rounded px-4 py-2 font-medium text-slate-600 hover:text-slate-900 disabled:opacity-50"
                >
                  Cancel booking
                </button>
              )}
              {view === 'held' && cancelStep !== 'idle' && (
                <div data-testid="cancel-question" className="flex flex-wrap items-center gap-3 text-sm text-slate-800">
                  <span>Release these seats?</span>
                  <button
                    type="button"
                    data-testid="cancel-yes"
                    onClick={() => void cancel()}
                    disabled={busy}
                    className="rounded bg-red-700 px-3 py-1.5 font-medium text-white disabled:opacity-50"
                  >
                    {cancelStep === 'cancelling' ? 'Cancelling…' : 'Yes, cancel'}
                  </button>
                  <button
                    type="button"
                    data-testid="cancel-no"
                    onClick={() => setCancelStep('idle')}
                    disabled={busy}
                    className="rounded border border-slate-300 px-3 py-1.5 font-medium text-slate-800 disabled:opacity-50"
                  >
                    Keep them
                  </button>
                </div>
              )}
            </div>
          )}

          <Link to={`/shows/${data.showId}`} className="mt-6 inline-block text-sm font-medium text-slate-900 underline">
            {view === 'held' ? 'Back to the seat map' : 'Choose seats for this show'}
          </Link>
        </article>
      )}
    </main>
  )
}

const HEADLINES = {
  held: 'held',
  lapsed: 'held',
  confirmed: 'confirmed',
  cancelled: 'was cancelled',
  expired: 'has expired',
} as const

/**
 * What to say for each reason a confirm was refused. `confirmed`, `cancelled` and `expired`
 * say nothing here: the page is already showing that state, which is the explanation.
 */
const REFUSALS: Record<ConfirmRefusal, string | null> = {
  confirmed: null,
  cancelled: null,
  expired: null,
  lapsed: 'Your hold expired before it could be confirmed. The seats have been released; you can choose them again.',
  'show-started': 'This show has started and can no longer be booked.',
  unavailable:
    'These seats can no longer be confirmed. The hold was lost, or a seat was sold to someone else. ' +
    'Go back to the seat map to choose again.',
}

function Details({ booking }: { booking: BookingResponse }) {
  // "Seat 43" is the ONE place a seat id reaches the screen, and only for a booking held
  // before V4 stored the labels: such a row has nothing else to call its seats. No such
  // row survives a re-seed, so this cannot appear in the demo. Deliberately not "fixed" by
  // fetching the show's seat map to look the label up - a conditional network call for a
  // case that barely occurs is complexity bought with nothing.
  const seats = joinList(
    booking.seats.map((seat) =>
      seat.rowLabel !== null && seat.seatNumber !== null
        ? seatLabel(seat.rowLabel, seat.seatNumber)
        : `Seat ${seat.showSeatId}`,
    ),
  )

  return (
    <dl className="mt-6 grid grid-cols-[auto_1fr] gap-x-6 gap-y-2 text-sm">
      {/* eventTitle, venueName and showStartsAt are null on a booking held before those
          fields were stored. The booking id in the heading is then the whole of its name. */}
      {booking.eventTitle && (
        <>
          <dt className="text-slate-500">Event</dt>
          <dd className="text-slate-900">{booking.eventTitle}</dd>
        </>
      )}
      {booking.venueName && (
        <>
          <dt className="text-slate-500">Venue</dt>
          <dd className="text-slate-900">{booking.venueName}</dd>
        </>
      )}
      {booking.showStartsAt && (
        <>
          <dt className="text-slate-500">Show</dt>
          <dd className="text-slate-900">{formatShowTime(booking.showStartsAt)}</dd>
        </>
      )}
      <dt className="text-slate-500">Seats</dt>
      <dd className="text-slate-900">{seats}</dd>
      <dt className="text-slate-500">Total</dt>
      <dd className="text-slate-900">{formatPrice(booking.totalAmount)}</dd>
    </dl>
  )
}

/**
 * The current time, re-read once a second while `ticking`. Stops when it is not needed:
 * a confirmed booking has nothing to count down to, and a timer per second for the life
 * of the tab would be work done for nothing.
 */
function useNow(ticking: boolean): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (!ticking) {
      return
    }
    setNow(Date.now())
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [ticking])
  return now
}
