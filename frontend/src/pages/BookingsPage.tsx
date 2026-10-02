import { useInfiniteQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'

import { listMyBookings } from '../api/bookings'
import { toApiError } from '../api/errors'
import type { BookingResponse, BookingStatus } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { ErrorNotice } from '../components/ErrorNotice'
import { viewOf, type BookingView } from '../lib/checkout'
import { formatClockTime, formatPrice, formatShowTime, joinList, seatLabel } from '../lib/format'

/**
 * The three tabs, and the statuses each one asks the API for.
 *
 * THE STATUS PARAMETER IS NEVER OMITTED. GET /api/bookings returns every status when it is
 * absent - there is no server-side default filter, by decision - and every abandoned hold
 * leaves a row for ever. An unfiltered list would put a load-tested account's one real
 * ticket on page seven behind forty expired holds. So there is no "All" tab, and
 * listMyBookings will not compile without at least one status.
 */
const TABS = [
  {
    key: 'tickets',
    label: 'Tickets',
    statuses: ['CONFIRMED'],
    empty: 'No tickets yet.',
    hint: 'A booking appears here once it is confirmed.',
  },
  {
    key: 'in-progress',
    label: 'In progress',
    statuses: ['PENDING'],
    empty: 'Nothing in progress.',
    hint: 'A booking appears here while its seats are held and waiting to be confirmed.',
  },
  {
    key: 'history',
    label: 'History',
    statuses: ['CANCELLED', 'EXPIRED'],
    empty: 'No cancelled or expired bookings.',
    hint: 'A booking you cancel, or one whose hold runs out, appears here.',
  },
] as const satisfies readonly {
  key: string
  label: string
  statuses: readonly [BookingStatus, ...BookingStatus[]]
  empty: string
  hint: string
}[]

type Tab = (typeof TABS)[number]

/** The tab named in ?tab=, or Tickets. Matched against the list above, never used raw. */
function tabFrom(param: string | null): Tab {
  return TABS.find((tab) => tab.key === param) ?? TABS[0]
}

/**
 * My Bookings: the signed-in user's bookings, in three tabs.
 *
 * PAGING IS A "LOAD MORE" BUTTON, not page numbers. The API pages at 20, newest first, and
 * a demo account holds a handful, so either would do; this is the one with less code - a
 * single button and no page number in the URL to validate - and it suits a list people
 * read from the top. It says "Showing 20 of 23" so the button's reach is not a mystery.
 *
 * THREE DIFFERENT KINDS OF "NOTHING HERE", kept apart:
 *  - the request failed: the shared error notice, with a retry;
 *  - this tab is empty: a sentence that says what WOULD appear in it, because "No tickets
 *    yet" on a new account and "nothing in progress" on a busy one are different facts and
 *    neither is "nothing matched your filter";
 *  - not signed in, or the session could not be checked: said as such.
 *
 * A PENDING BOOKING WHOSE HOLD HAS RUN OUT IS SHOWN AS EXPIRED. The server goes on saying
 * PENDING for up to a minute, until the sweeper marks it, and until then it is still in
 * the "In progress" tab - but it is not in progress, and offering "Resume checkout" on a
 * dead hold would send the user to a page that can only refuse them. The decision is
 * lib/checkout's viewOf, the same one the checkout page makes.
 */
export function BookingsPage() {
  const { auth } = useAuth()
  const userId = auth.status === 'signedIn' ? auth.user.id : null
  const [searchParams] = useSearchParams()
  const tab = tabFrom(searchParams.get('tab'))

  const bookings = useInfiniteQuery({
    // Under ['bookings'], so a hold, a confirm or a cancel elsewhere refreshes it.
    queryKey: ['bookings', 'list', userId, tab.key],
    queryFn: ({ pageParam }) => listMyBookings(tab.statuses, pageParam),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.last ? undefined : last.page + 1),
    enabled: userId !== null,
    staleTime: 0,
  })

  const rows = bookings.data?.pages.flatMap((page) => page.content) ?? []
  const total = bookings.data?.pages[0]?.totalElements ?? 0
  // Re-read the clock now and then, so a hold that runs out while the list is open stops
  // being offered as resumable without a reload.
  const now = useNow(tab.key === 'in-progress' && rows.length > 0)

  return (
    <main className="mx-auto max-w-3xl px-4 py-8">
      <h1 className="text-2xl font-semibold text-slate-900">My bookings</h1>

      <nav aria-label="Booking lists" className="mt-4 flex gap-1 border-b border-slate-200">
        {TABS.map((candidate) => (
          <Link
            key={candidate.key}
            to={`/bookings?tab=${candidate.key}`}
            aria-current={candidate.key === tab.key ? 'page' : undefined}
            className={`-mb-px border-b-2 px-3 py-2 text-sm font-medium ${
              candidate.key === tab.key
                ? 'border-slate-900 text-slate-900'
                : 'border-transparent text-slate-500 hover:text-slate-900'
            }`}
          >
            {candidate.label}
          </Link>
        ))}
      </nav>

      <div className="mt-6">
        {auth.status === 'restoring' && <p className="text-slate-500">Checking session…</p>}

        {auth.status === 'signedOut' && (
          <div className="rounded-md border border-slate-200 bg-white p-6">
            <p className="font-medium text-slate-900">Log in to see your bookings.</p>
            <Link to="/login" className="mt-2 inline-block text-sm font-medium text-slate-900 underline">
              Log in
            </Link>
          </div>
        )}

        {auth.status === 'unknown' && (
          <p className="rounded-md border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">
            Your session could not be checked, so your bookings cannot be shown yet. Use "Check again" at the top of
            the page.
          </p>
        )}

        {userId !== null && bookings.isPending && <p className="text-slate-500">Loading bookings…</p>}

        {userId !== null && bookings.isError && rows.length === 0 && (
          <ErrorNotice
            action="load your bookings"
            error={toApiError(bookings.error)}
            onRetry={() => void bookings.refetch()}
          />
        )}

        {userId !== null && bookings.isSuccess && rows.length === 0 && (
          <div data-testid="bookings-empty" className="rounded-md border border-slate-200 bg-white p-6">
            <p className="font-medium text-slate-900">{tab.empty}</p>
            <p className="mt-1 text-sm text-slate-600">{tab.hint}</p>
            <Link to="/" className="mt-3 inline-block text-sm font-medium text-slate-900 underline">
              Browse events
            </Link>
          </div>
        )}

        {rows.length > 0 && (
          <>
            <ul data-testid="bookings-list" className="space-y-3">
              {rows.map((booking) => (
                <li key={booking.id}>
                  <BookingRow booking={booking} view={viewOf(booking, now)} />
                </li>
              ))}
            </ul>

            <div className="mt-4 flex items-center justify-between gap-3 text-sm text-slate-600">
              <p data-testid="bookings-count">
                Showing {rows.length} of {total}
              </p>
              {bookings.hasNextPage && (
                <button
                  type="button"
                  data-testid="load-more"
                  onClick={() => void bookings.fetchNextPage()}
                  disabled={bookings.isFetchingNextPage}
                  className="rounded border border-slate-300 bg-white px-3 py-1.5 font-medium text-slate-900 disabled:opacity-50"
                >
                  {bookings.isFetchingNextPage ? 'Loading…' : 'Load more'}
                </button>
              )}
            </div>

            {/* A later page that failed: the rows already loaded stay, and this says why
                there are no more. */}
            {bookings.isError && (
              <div className="mt-3">
                <ErrorNotice
                  action="load more bookings"
                  error={toApiError(bookings.error)}
                  onRetry={() => void bookings.fetchNextPage()}
                />
              </div>
            )}
          </>
        )}
      </div>
    </main>
  )
}

const BADGES: Record<BookingView, { text: string; classes: string }> = {
  confirmed: { text: 'Confirmed', classes: 'bg-emerald-100 text-emerald-900' },
  held: { text: 'Held', classes: 'bg-amber-100 text-amber-900' },
  // PENDING on the server, dead on the clock. Shown as what it is.
  lapsed: { text: 'Expired', classes: 'bg-slate-200 text-slate-700' },
  expired: { text: 'Expired', classes: 'bg-slate-200 text-slate-700' },
  cancelled: { text: 'Cancelled', classes: 'bg-slate-200 text-slate-700' },
}

function BookingRow({ booking, view }: { booking: BookingResponse; view: BookingView }) {
  // "Seat 43" only for a booking held before V4 stored the labels - see BookingPage.
  const seats = joinList(
    booking.seats.map((seat) =>
      seat.rowLabel !== null && seat.seatNumber !== null
        ? seatLabel(seat.rowLabel, seat.seatNumber)
        : `Seat ${seat.showSeatId}`,
    ),
  )

  return (
    <Link
      to={`/bookings/${booking.id}`}
      data-testid="booking-row"
      data-view={view}
      className="block rounded-lg border border-slate-200 bg-white p-4 hover:border-slate-400"
    >
      <div className="flex items-start justify-between gap-3">
        {/* eventTitle is null on a booking held before it was stored; "Booking 12" is then
            its name, and is expected rather than a bug. */}
        <h2 className="font-semibold text-slate-900">{booking.eventTitle ?? `Booking ${booking.id}`}</h2>
        <span className={`shrink-0 rounded px-2 py-0.5 text-xs font-medium ${BADGES[view].classes}`}>
          {BADGES[view].text}
        </span>
      </div>
      <p className="mt-1 text-sm text-slate-600">
        {booking.venueName && `${booking.venueName} · `}
        {booking.showStartsAt && formatShowTime(booking.showStartsAt)}
      </p>
      <p className="mt-2 text-sm text-slate-800">
        {seats} · {formatPrice(booking.totalAmount)} · Booking {booking.id}
      </p>
      {view === 'held' && booking.expiresAt && (
        <p className="mt-2 text-sm font-medium text-amber-900">
          Seats held until {formatClockTime(booking.expiresAt)}. Resume checkout →
        </p>
      )}
    </Link>
  )
}

/** The current time, re-read every ten seconds while `ticking`. */
function useNow(ticking: boolean): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (!ticking) {
      return
    }
    setNow(Date.now())
    const timer = setInterval(() => setNow(Date.now()), 10_000)
    return () => clearInterval(timer)
  }, [ticking])
  return now
}
