import { useQuery } from '@tanstack/react-query'
import { Link, useParams } from 'react-router-dom'

import { getBooking } from '../api/bookings'
import { toApiError } from '../api/errors'
import type { BookingResponse } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { ErrorNotice } from '../components/ErrorNotice'
import { formatClockTime, formatPrice, formatShowTime, joinList, seatLabel } from '../lib/format'
import { parseId } from '../lib/routeParams'

/**
 * Where a successful hold lands. A STUB, on purpose.
 *
 * It says which booking was held, for what, and when the hold runs out - and nothing else.
 * No countdown, no confirm, no cancel: those are the next commit, and a half-built
 * checkout is harder to debug than none. What this proves is the end of the chain the seat
 * map starts: the hold was created, it belongs to this user, and it can be read back.
 *
 * A booking that is somebody else's is a 404 from the API, identical to one that does not
 * exist - the API does not say which, and neither does this page.
 */
export function BookingPage() {
  const id = parseId(useParams().id)
  const { auth } = useAuth()
  const userId = auth.status === 'signedIn' ? auth.user.id : null

  const booking = useQuery({
    queryKey: ['bookings', 'one', userId, id],
    queryFn: () => getBooking(id as number),
    enabled: id !== null && userId !== null,
  })

  const error = booking.isError ? toApiError(booking.error) : null
  const notFound = id === null || error?.status === 404

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

      {userId !== null && !notFound && booking.isPending && <p className="text-slate-500">Loading booking…</p>}

      {userId !== null && !notFound && error && (
        <ErrorNotice action="load this booking" error={error} onRetry={() => void booking.refetch()} />
      )}

      {booking.isSuccess && <Held booking={booking.data} />}
    </main>
  )
}

function Held({ booking }: { booking: BookingResponse }) {
  // A PENDING booking whose expiry has passed holds nothing, whatever its status still says:
  // the sweeper marks it EXPIRED up to a minute later.
  const lapsed =
    booking.status === 'PENDING' && booking.expiresAt !== null && new Date(booking.expiresAt).getTime() <= Date.now()

  const seats = joinList(
    booking.seats.map((seat) =>
      seat.rowLabel !== null && seat.seatNumber !== null
        ? seatLabel(seat.rowLabel, seat.seatNumber)
        : `seat ${seat.showSeatId}`,
    ),
  )

  return (
    <article data-testid="booking" className="rounded-lg border border-slate-200 bg-white p-6">
      <h1 className="text-2xl font-semibold text-slate-900">
        Booking {booking.id} {headline(booking, lapsed)}
      </h1>

      {booking.status === 'PENDING' && !lapsed && booking.expiresAt && (
        <p data-testid="booking-expiry" className="mt-1 text-slate-700">
          Your seats are held until {formatClockTime(booking.expiresAt)}.
        </p>
      )}

      <dl className="mt-6 grid grid-cols-[auto_1fr] gap-x-6 gap-y-2 text-sm">
        {/* eventTitle, venueName and showStartsAt are null on a booking held before those
            fields were stored. The booking id above is then the whole of its name. */}
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

      <Link to={`/shows/${booking.showId}`} className="mt-6 inline-block text-sm font-medium text-slate-900 underline">
        Back to the seat map
      </Link>
    </article>
  )
}

function headline(booking: BookingResponse, lapsed: boolean): string {
  if (lapsed) {
    return 'has expired'
  }
  switch (booking.status) {
    case 'PENDING':
      return 'held'
    case 'CONFIRMED':
      return 'confirmed'
    case 'CANCELLED':
      return 'was cancelled'
    case 'EXPIRED':
      return 'has expired'
  }
}
