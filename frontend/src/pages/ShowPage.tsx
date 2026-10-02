import { useQuery, type Query } from '@tanstack/react-query'
import { useCallback } from 'react'
import { Link, useParams } from 'react-router-dom'

import { toApiError } from '../api/errors'
import { getSeatMap } from '../api/shows'
import type { SeatMapResponse, SeatResponse } from '../api/types'
import { ErrorNotice } from '../components/ErrorNotice'
import { SeatGrid, SeatLegend, type SeatView } from '../components/SeatGrid'
import { formatShowTime } from '../lib/format'
import { parseId } from '../lib/routeParams'

/** How often the seat map is re-read while the tab is visible. */
const POLL_INTERVAL_MS = 5_000

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
 * The seat map for one show.
 *
 * =======================================================================================
 * WHAT THIS PAGE CAN AND CANNOT KNOW
 * =======================================================================================
 * The API reports two statuses, AVAILABLE and BOOKED. A seat somebody is part-way through
 * buying reads AVAILABLE: holds are never written to the database. So this map shows what
 * has been SOLD, promptly, and says nothing about what is being bought right now. That is
 * the design, not a gap in this page.
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
 */
export function ShowPage() {
  const showId = parseId(useParams().id)

  const seatMap = useQuery({
    queryKey: ['shows', showId, 'seats'],
    queryFn: () => getSeatMap(showId as number),
    enabled: showId !== null,
    refetchInterval: nextPollIn,
    staleTime: 0,
    retry: false,
  })

  const viewOf = useCallback((seat: SeatResponse): SeatView => (seat.status === 'BOOKED' ? 'sold' : 'available'), [])

  const error = seatMap.isError ? toApiError(seatMap.error) : null
  const notFound = showId === null || (error?.status === 404 && !seatMap.data)
  const map = seatMap.data

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

      {map && (
        <>
          <Link to={`/events/${map.eventId}`} className="text-sm text-slate-600 hover:text-slate-900">
            ← {map.eventTitle}
          </Link>
          <h1 className="mt-2 text-2xl font-semibold text-slate-900">{map.eventTitle}</h1>
          <p className="text-slate-600">
            {map.venueName} · {formatShowTime(map.startsAt)}
          </p>

          {hasStarted(map.startsAt) && (
            <p data-testid="show-started" className="mt-4 rounded-md border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">
              This show has started. Its seats can no longer be booked.
            </p>
          )}

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
            />
          </div>

          <p className="mt-3 text-xs text-slate-500">
            A seat someone else is in the middle of booking still shows as available. This map shows what has been
            sold; it refreshes every {POLL_INTERVAL_MS / 1000} seconds.
          </p>
        </>
      )}
    </main>
  )
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
