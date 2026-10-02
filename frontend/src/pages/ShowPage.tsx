import { useMutation, useQuery, useQueryClient, type Query } from '@tanstack/react-query'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'

import { holdSeats, listMyPendingBookings } from '../api/bookings'
import { toApiError, type ApiError } from '../api/errors'
import { getSeatMap } from '../api/shows'
import type { BookingResponse, SeatMapResponse, SeatResponse } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { ErrorNotice } from '../components/ErrorNotice'
import { SeatGrid, SeatLegend, type SeatView } from '../components/SeatGrid'
import { formatClockTime, formatPrice, formatShowTime, joinList, seatLabel } from '../lib/format'
import { viewOf as bookingViewOf } from '../lib/checkout'
import { returnToShow } from '../lib/returnToShow'
import { parseId } from '../lib/routeParams'
import {
  MAX_SEATS_PER_BOOKING,
  applyConflict,
  emptySelection,
  isMarkedTaken,
  reconcile,
  toggleSeat,
  type SelectionState,
} from '../lib/seatSelection'

/** How often the seat map is re-read while the tab is visible. */
const POLL_INTERVAL_MS = 5_000

/** A fresh Idempotency-Key. The API requires a UUID. */
const newKey = () => crypto.randomUUID()

/**
 * Why the eleventh seat does not select. Said in full, because a seat that silently refuses
 * a click looks broken: the limit is the API's (CreateBookingRequest), not a whim of this
 * page, and there is a way to get more than ten seats.
 */
const LIMIT_NOTICE =
  `A booking can hold at most ${MAX_SEATS_PER_BOOKING} seats, so that seat was not added. ` +
  'Unselect one to choose a different seat, or hold these and make a second booking for the rest.'

/**
 * When to poll next. Normally five seconds on; never again once the show is a 404.
 *
 * AFTER A 429, NOT BEFORE THE GATEWAY SAYS SO. A poll that keeps firing into a rate
 * limiter keeps the client limited, and every one of those requests is also taken from
 * the budget the user's own clicks need. So the next poll waits for the Retry-After the
 * gateway sent, if that is longer than the normal interval.
 */
function nextPollIn(query: Query<SeatMapResponse>): number | false {
  const error = query.state.error ? toApiError(query.state.error) : null
  // A show that does not exist will not start existing. Polling a 404 every five seconds
  // for as long as the tab is open is requests spent to be told the same thing.
  if (error?.status === 404) {
    return false
  }
  if (error?.retryAfterSeconds) {
    return Math.max(error.retryAfterSeconds * 1000, POLL_INTERVAL_MS)
  }
  return POLL_INTERVAL_MS
}

/**
 * The seat map for one show: look, pick, hold.
 *
 * =======================================================================================
 * WHAT THIS PAGE CAN AND CANNOT KNOW
 * =======================================================================================
 * The API reports two statuses, AVAILABLE and BOOKED. A seat somebody is part-way through
 * buying reads AVAILABLE: holds are never written to the database. So this map shows what
 * has been SOLD, promptly, and says nothing about what is being bought right now. That is
 * the design, not a gap in this page - and it is why a user can click a free-looking seat
 * and be refused. Everything below "selection and hold" is about making that refusal read
 * as a busy show rather than a broken app.
 *
 * =======================================================================================
 * POLLING
 * =======================================================================================
 * Every five seconds while the tab is visible, and once on returning to it. Not in the
 * background: a hidden tab stops, which is TanStack Query's default and is left alone.
 * That is 0.2 requests a second against a rate limit that refills at 2.
 *
 * Retries are OFF for this query, unlike every other. The next poll IS the retry. With the
 * app-wide policy a failing poll would be three requests every five seconds - against a
 * backend that is already struggling, or a limiter that has already said no.
 *
 * WHEN A POLL FAILS, THE MAP STAYS. The last good map is kept on screen with a line saying
 * it could not be refreshed and how old it is. Blanking a map the user is reading because
 * one request failed would be worse than showing it slightly stale and saying so.
 *
 * WHEN THE BROWSER IS OFFLINE, NOTHING FAILS - and that needed its own line. TanStack Query
 * does not send a request it knows cannot leave the machine: the poll is PAUSED, not
 * failed, so there is no error to show. Without the check on fetchStatus below, a user
 * whose wifi dropped would keep looking at a map that had silently stopped refreshing.
 * Found by taking the tab offline in a browser and watching no message appear.
 *
 * =======================================================================================
 * SELECTION AND HOLD
 * =======================================================================================
 * The rules live in lib/seatSelection as plain functions; this component only calls them.
 *
 *  - NOTHING IS OPTIMISTIC. Picking a seat changes local state and sends nothing. The Hold
 *    button says "Holding…" until the server answers; no seat is drawn as held on a guess.
 *
 *  - A 409 WITH conflictingSeatIds: those seats leave the selection, the rest stay, each
 *    is drawn as "being booked by someone else", and one line names them. The mark is a
 *    warning for ten minutes, not a lock.
 *
 *  - A 409 WITHOUT that field (a seat already sold, or the show has started): the page says
 *    so in its own words and re-reads the map, which names the seat by its label. The
 *    server's message carries a database id and goes to the console, not the screen. The
 *    branch is on whether the field is there. Nothing looks at the wording.
 *
 *  - THE USER'S OWN HOLDS are drawn as theirs. They read AVAILABLE in the map like any
 *    other held seat, so without the PENDING-bookings query a user returning to a show
 *    would click their own seat and be told somebody else has it.
 *
 *  - THE IDEMPOTENCY KEY belongs to the seat set and changes when it does. Trying the same
 *    seats again after a failure sends the same key.
 *
 *  - SIGNED OUT, the page is read-only: the map, the legend and the polling all work, and
 *    a banner says why nothing can be picked. Logging in from it comes back to this show.
 */
export function ShowPage() {
  const showId = parseId(useParams().id)
  const { auth } = useAuth()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const userId = auth.status === 'signedIn' ? auth.user.id : null
  // Set by the checkout page when it sends the user here after a cancel. A number in
  // history state, and the sentence is built here: the page never prints text that
  // arrived from somewhere else.
  const cancelledBookingId = cancelledBookingIdFrom(useLocation().state)

  const seatMap = useQuery({
    queryKey: ['shows', showId, 'seats'],
    queryFn: () => getSeatMap(showId as number),
    enabled: showId !== null,
    refetchInterval: nextPollIn,
    staleTime: 0,
    retry: false,
  })

  // Not polled: re-read on focus, and after a hold. Its seats also fall away by themselves
  // when their expiresAt passes - see `mine` below.
  const pending = useQuery({
    queryKey: ['bookings', 'pending', userId],
    queryFn: listMyPendingBookings,
    enabled: userId !== null && showId !== null,
    staleTime: 0,
  })

  const [selection, setSelection] = useState<SelectionState>(() => emptySelection(newKey))
  // The same state, readable from callbacks that must keep a stable identity (the grid's
  // seats are memoised on them). Written only through `update`.
  const selectionRef = useRef(selection)
  const update = useCallback((next: SelectionState) => {
    selectionRef.current = next
    setSelection(next)
  }, [])

  /** One line about the selection: a conflict, a dropped seat, the limit. */
  const [notice, setNotice] = useState<string | null>(null)
  /** A hold that failed in a way that is NOT a 409: unreachable, 429, 5xx. */
  const [holdError, setHoldError] = useState<ApiError | null>(null)

  const map = seatMap.data
  const started = map ? hasStarted(map.startsAt) : false
  const interactive = userId !== null && !started

  const seatsById = useMemo(() => {
    const byId = new Map<number, SeatResponse>()
    map?.rows.forEach((row) => row.seats.forEach((seat) => byId.set(seat.id, seat)))
    return byId
  }, [map])

  /**
   * The user's live holds on THIS show. "Live" is lib/checkout's viewOf saying `held`
   * (imported as bookingViewOf: this page has a viewOf of its own, for seats): a
   * booking can read PENDING for up to a minute after its expiresAt has passed, until the
   * sweeper gets to it, and such a booking holds nothing. Recomputed on every poll (the
   * dataUpdatedAt dependency), which is what lets an expired hold fall away by itself.
   *
   * This used to be its own four-line copy of that rule - status PENDING, expiry not null,
   * expiry after now - sitting beside the two pages that call viewOf. Three places agreeing
   * by coincidence is two more than can be kept in step; the copy here had already drifted
   * in one detail nobody chose (it had no opinion about a PENDING booking with no expiry
   * because it never asked). One function now, and it is the one the tests pin.
   */
  const myHolds = useMemo(
    () =>
      (pending.data ?? []).filter(
        (booking) => booking.showId === showId && bookingViewOf(booking, Date.now()) === 'held',
      ),
    [pending.data, showId, seatMap.dataUpdatedAt],
  )
  const mySeatIds = useMemo(
    () => new Set(myHolds.flatMap((booking) => booking.seats.map((seat) => seat.showSeatId))),
    [myHolds],
  )

  /** Seats that cannot be in a selection: sold ones, and the user's own held ones. */
  const unavailable = useMemo(() => {
    const ids = new Set(mySeatIds)
    seatsById.forEach((seat) => {
      if (seat.status === 'BOOKED') {
        ids.add(seat.id)
      }
    })
    return ids
  }, [seatsById, mySeatIds])

  /**
   * "C4" for a show_seats id, from the map this page is showing. A seat id is never put in
   * front of the user while a label can be had, and here one always can: every id this
   * page handles - a selection, a conflict, one of the user's own holds - is a seat of this
   * show. The "a seat" fallback is for an id that is somehow not in the map; it names
   * nothing rather than printing a number.
   */
  const labelOf = useCallback(
    (seatId: number) => {
      const seat = seatsById.get(seatId)
      return seat ? seatLabel(seat.rowLabel, seat.seatNumber) : 'a seat'
    },
    [seatsById],
  )
  const labelsOf = useCallback((seatIds: readonly number[]) => seatIds.map(labelOf), [labelOf])

  // After every poll: drop a selected seat that has been sold (and say so), and clear
  // marks that are ten minutes old or whose seat the map now shows as sold.
  useEffect(() => {
    const result = reconcile(selectionRef.current, unavailable, Date.now(), newKey)
    if (result.state !== selectionRef.current) {
      update(result.state)
      if (result.dropped.length > 0) {
        const names = labelsOf(result.dropped)
        const dropped =
          `${joinList(names)} ${names.length === 1 ? 'is' : 'are'} no longer available and ` +
          `${names.length === 1 ? 'was' : 'were'} taken off your selection.`
        // Added to a notice already showing, not put in its place. After a 409 for a sold
        // seat the server's own message is on screen, the map is re-read, and this fires
        // a moment later for the same seat - replacing the message would mean it was
        // never readable.
        setNotice((current) => (current ? `${current.replace(/[.\s]*$/, '.')} ${dropped}` : dropped))
      }
    }
  }, [unavailable, seatMap.dataUpdatedAt, update, labelsOf])

  const selectedIds = useMemo(() => new Set(selection.selected), [selection.selected])

  const viewOf = useCallback(
    (seat: SeatResponse): SeatView => {
      if (seat.status === 'BOOKED') {
        return 'sold'
      }
      if (mySeatIds.has(seat.id)) {
        return 'mine'
      }
      if (selectedIds.has(seat.id)) {
        return 'selected'
      }
      return isMarkedTaken(selection, seat.id, Date.now()) ? 'taken' : 'available'
    },
    // dataUpdatedAt: so a ten-minute-old mark is re-evaluated on each poll.
    [mySeatIds, selectedIds, selection, seatMap.dataUpdatedAt],
  )

  const onToggle = useCallback(
    (seatId: number) => {
      const result = toggleSeat(selectionRef.current, seatId, newKey)
      if (result.refused === 'limit') {
        setNotice(LIMIT_NOTICE)
        return
      }
      update(result.state)
      setNotice(null)
      setHoldError(null)
    },
    [update],
  )

  const hold = useMutation({
    mutationFn: (attempt: { seatIds: number[]; idempotencyKey: string }) =>
      holdSeats({ showId: showId as number, seatIds: attempt.seatIds }, attempt.idempotencyKey),
  })

  function submitHold() {
    const { selected, idempotencyKey } = selectionRef.current
    setNotice(null)
    setHoldError(null)
    hold.mutate(
      { seatIds: [...selected], idempotencyKey },
      {
        onSuccess: (booking) => {
          // The header's count and this page's own-holds query are both under ['bookings'].
          void queryClient.invalidateQueries({ queryKey: ['bookings'] })
          navigate(`/bookings/${booking.id}`)
        },
        onError: (caught) => {
          const error = toApiError(caught)
          if (error.status !== 409) {
            // Unreachable, 429, 5xx, or a 4xx that is not about seats. The selection AND
            // its key are left exactly as they were: "Try again" sends the same request.
            setHoldError(error)
            return
          }
          if (error.conflictingSeatIds !== undefined) {
            const names = labelsOf(error.conflictingSeatIds)
            const next = applyConflict(selectionRef.current, error.conflictingSeatIds, Date.now(), newKey)
            update(next)
            setNotice(
              `${joinList(names)} ${names.length === 1 ? 'was' : 'were'} just taken by someone else. ` +
                (next.selected.length > 0
                  ? `${next.selected.length} ${next.selected.length === 1 ? 'seat is' : 'seats are'} still selected.`
                  : 'Nothing is selected now.'),
            )
          } else {
            // Sold, or the show has started. NOT the server's words: its message is
            // "Seats not available: [43]", and 43 is a database id that means nothing to
            // the person reading it. The page says what it can say in its own terms, and
            // the re-read of the map below names the seat by its label - the reconcile
            // effect adds "E3 is no longer available and was taken off your selection".
            // The server's sentence is kept for whoever has the console open.
            console.info('Hold refused (409 without conflictingSeatIds):', error.message)
            setNotice(
              map && hasStarted(map.startsAt)
                ? 'This show has started, so its seats can no longer be booked.'
                : 'Those seats could not be held.',
            )
          }
          void seatMap.refetch()
          void pending.refetch()
        },
      },
    )
  }

  const error = seatMap.isError ? toApiError(seatMap.error) : null
  const notFound = showId === null || (error?.status === 404 && !map)
  const selectedSeats = selection.selected.flatMap((id) => seatsById.get(id) ?? [])
  const total = selectedSeats.reduce((sum, seat) => sum + seat.price, 0)

  return (
    <main className="mx-auto max-w-5xl px-4 py-8">
      {notFound && (
        <div data-testid="show-not-found" className="rounded-md border border-slate-200 bg-white p-6">
          <h1 className="text-xl font-semibold text-slate-900">Show not found</h1>
          <p className="mt-1 text-sm text-slate-600">There is no show at this address.</p>
          <Link to="/" className="mt-3 inline-block text-sm font-medium text-slate-900 underline">
            All events
          </Link>
        </div>
      )}

      {!notFound && !map && seatMap.isPending && <p className="text-slate-500">Loading seat map…</p>}

      {/* No map at all and the request failed: the full notice, with a retry. */}
      {!notFound && !map && error && (
        <ErrorNotice action="load the seat map" error={error} onRetry={() => void seatMap.refetch()} />
      )}

      {map && showId !== null && (
        <>
          <Link to={`/events/${map.eventId}`} className="text-sm text-slate-600 hover:text-slate-900">
            ← {map.eventTitle}
          </Link>
          <h1 className="mt-2 text-2xl font-semibold text-slate-900">{map.eventTitle}</h1>
          <p className="text-slate-600">
            {map.venueName} · {formatShowTime(map.startsAt)}
          </p>

          {started && (
            <p data-testid="show-started" className="mt-4 rounded-md border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">
              This show has started. Its seats can no longer be booked.
            </p>
          )}

          {!started && auth.status === 'signedOut' && (
            <p data-testid="signed-out-banner" className="mt-4 rounded-md border border-slate-300 bg-white p-3 text-sm text-slate-800">
              <Link to="/login" state={returnToShow(showId)} className="font-medium text-slate-900 underline">
                Log in
              </Link>{' '}
              or{' '}
              <Link to="/register" state={returnToShow(showId)} className="font-medium text-slate-900 underline">
                register
              </Link>{' '}
              to choose seats. You can see what is available without an account.
            </p>
          )}

          {!started && auth.status === 'unknown' && (
            <p className="mt-4 rounded-md border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">
              Your session could not be checked, so seats cannot be chosen right now. Use "Check again" at the top of
              the page.
            </p>
          )}

          {cancelledBookingId !== null && (
            <p data-testid="cancelled-line" role="status" className="mt-4 rounded-md border border-emerald-200 bg-emerald-50 p-3 text-sm text-emerald-900">
              Booking {cancelledBookingId} cancelled. Your seats are free again.
            </p>
          )}

          <OwnHolds bookings={myHolds} labelOf={labelOf} />

          <div className="mt-6 flex flex-wrap items-center justify-between gap-3">
            <p data-testid="availability" className="text-sm font-medium text-slate-900">
              {map.availableSeats} of {map.totalSeats} seats available
            </p>
            <SeatLegend />
          </div>

          {/* A map IS on screen and it is not being refreshed: one line, and the map stays. */}
          {!error && seatMap.fetchStatus === 'paused' && (
            <p data-testid="stale-map" role="status" className="mt-3 rounded-md border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">
              You appear to be offline, so the seat map is not refreshing. The map below is as of{' '}
              {new Date(seatMap.dataUpdatedAt).toLocaleTimeString()}; it will refresh when the connection returns.
            </p>
          )}
          {error && (
            <p data-testid="stale-map" role="status" className="mt-3 rounded-md border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">
              {staleReason(error.kind, error.status)} The map below is as of{' '}
              {new Date(seatMap.dataUpdatedAt).toLocaleTimeString()}; it will refresh by itself.
            </p>
          )}

          <div className="mt-4">
            <SeatGrid
              label={`Seat map for ${map.eventTitle}, ${formatShowTime(map.startsAt)}`}
              rows={map.rows}
              viewOf={viewOf}
              interactive={interactive}
              onToggle={onToggle}
            />
          </div>

          <p className="mt-3 text-xs text-slate-500">
            A seat someone else is in the middle of booking still shows as available. This map shows what has been
            sold; it refreshes every {POLL_INTERVAL_MS / 1000} seconds.
          </p>

          {interactive && (
            <section aria-label="Your selection" className="mt-6 rounded-lg border border-slate-200 bg-white p-4">
              {/* Polite, so a screen reader hears the selection change and the conflict
                  line without being interrupted mid-sentence. */}
              <div aria-live="polite">
                {notice && (
                  <p data-testid="selection-notice" className="mb-3 rounded-md border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">
                    {notice}
                  </p>
                )}
                <p data-testid="selection-summary" className="text-sm text-slate-800">
                  {selectedSeats.length === 0
                    ? `No seats selected. Choose up to ${MAX_SEATS_PER_BOOKING}.`
                    : `${selectedSeats.length} ${selectedSeats.length === 1 ? 'seat' : 'seats'} selected: ` +
                      `${joinList(selectedSeats.map((seat) => seatLabel(seat.rowLabel, seat.seatNumber)))} · ${formatPrice(total)}`}
                </p>
              </div>

              <div className="mt-3">
                {holdError ? (
                  // Replaces the button, so there is exactly one way to send the request
                  // again and it is the one that waits out a 429's Retry-After.
                  <ErrorNotice action="hold these seats" error={holdError} onRetry={submitHold} />
                ) : (
                  <button
                    type="button"
                    data-testid="hold-button"
                    onClick={submitHold}
                    disabled={selectedSeats.length === 0 || hold.isPending}
                    className="rounded bg-slate-900 px-4 py-2 font-medium text-white disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    {hold.isPending
                      ? 'Holding…'
                      : selectedSeats.length === 0
                        ? 'Hold seats'
                        : `Hold ${selectedSeats.length} ${selectedSeats.length === 1 ? 'seat' : 'seats'} for 10 minutes`}
                  </button>
                )}
              </div>
            </section>
          )}
        </>
      )}
    </main>
  )
}

/**
 * "You are holding A3 and A4 until 6:52:10 pm IST. Resume checkout." One line per booking.
 *
 * Labels come from the seat map (labelOf), not from the booking's own rowLabel and
 * seatNumber. Those are null on a booking held before they were stored, and this page
 * has the map in hand either way.
 */
function OwnHolds({ bookings, labelOf }: { bookings: BookingResponse[]; labelOf: (seatId: number) => string }) {
  if (bookings.length === 0) {
    return null
  }
  return (
    <ul data-testid="own-holds" className="mt-4 space-y-2">
      {bookings.map((booking) => (
        <li key={booking.id} className="rounded-md border border-emerald-200 bg-emerald-50 p-3 text-sm text-emerald-900">
          You are holding{' '}
          {joinList(booking.seats.map((seat) => labelOf(seat.showSeatId)))}
          {booking.expiresAt && ` until ${formatClockTime(booking.expiresAt)}`}.{' '}
          <Link to={`/bookings/${booking.id}`} className="font-medium underline">
            Resume checkout
          </Link>
        </li>
      ))}
    </ul>
  )
}

function cancelledBookingIdFrom(state: unknown): number | null {
  const id =
    typeof state === 'object' && state !== null ? (state as Record<string, unknown>).cancelledBookingId : undefined
  return typeof id === 'number' && Number.isSafeInteger(id) && id > 0 ? id : null
}

/** Against the viewer's clock. The server decides for real; this only sets expectations. */
function hasStarted(startsAt: string): boolean {
  return new Date(startsAt).getTime() <= Date.now()
}

function staleReason(kind: 'unreachable' | 'http', status: number | undefined): string {
  if (kind === 'unreachable') {
    return 'Could not refresh the seat map: the server did not answer.'
  }
  if (status === 429) {
    return 'Could not refresh the seat map: too many requests.'
  }
  return `Could not refresh the seat map (HTTP ${status}).`
}
