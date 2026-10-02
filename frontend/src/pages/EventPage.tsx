import { useQuery } from '@tanstack/react-query'
import { Link, useParams } from 'react-router-dom'

import { toApiError } from '../api/errors'
import { getEvent } from '../api/events'
import { ErrorNotice } from '../components/ErrorNotice'
import { Poster } from '../components/EventCard'
import { formatPrice, formatShowTime } from '../lib/format'
import { parseId } from '../lib/routeParams'

/**
 * One event: what it is, where, and the shows that can still be booked.
 *
 * FOUR OUTCOMES, each said differently:
 *
 *  - No such event - a 404 from the API, or an id in the URL that is not a number. "Not
 *    found", with a way back. Not the generic error notice: nothing went wrong.
 *  - The request failed some other way. ErrorNotice, with a retry.
 *  - The event exists and has NO upcoming shows. A real, reachable state, unlike on the
 *    browse page: the list hides such an event, but a bookmark or a shared link still
 *    resolves. Said plainly, so it does not look like a page that failed to load its shows.
 *  - The event and its shows, each show a link to its seat map.
 */
export function EventPage() {
  const id = parseId(useParams().id)

  const event = useQuery({
    queryKey: ['events', id],
    queryFn: () => getEvent(id as number),
    enabled: id !== null,
  })

  const error = event.isError ? toApiError(event.error) : null
  const notFound = id === null || error?.status === 404

  return (
    <main className="mx-auto max-w-3xl px-4 py-8">
      <Link to="/" className="text-sm text-slate-600 hover:text-slate-900">
        ← All events
      </Link>

      <div className="mt-4">
        {notFound && (
          <div data-testid="event-not-found" className="rounded-md border border-slate-200 bg-white p-6">
            <h1 className="text-xl font-semibold text-slate-900">Event not found</h1>
            <p className="mt-1 text-sm text-slate-600">There is no event at this address.</p>
          </div>
        )}

        {!notFound && event.isPending && <p className="text-slate-500">Loading event…</p>}

        {!notFound && error && (
          <ErrorNotice action="load this event" error={error} onRetry={() => void event.refetch()} />
        )}

        {event.isSuccess && (
          <article className="overflow-hidden rounded-lg border border-slate-200 bg-white">
            <Poster title={event.data.title} url={event.data.posterUrl} />
            <div className="p-6">
              <p className="text-xs font-medium uppercase tracking-wide text-slate-500">{event.data.category}</p>
              <h1 className="mt-1 text-2xl font-semibold text-slate-900">{event.data.title}</h1>
              <p className="mt-1 text-slate-600">
                {event.data.venue.name}, {event.data.venue.city}
              </p>
              {event.data.venue.address && <p className="text-sm text-slate-500">{event.data.venue.address}</p>}
              {event.data.description && <p className="mt-4 text-slate-700">{event.data.description}</p>}

              <h2 className="mt-8 text-lg font-semibold text-slate-900">Upcoming shows</h2>

              {event.data.upcomingShows.length === 0 ? (
                <p data-testid="no-upcoming-shows" className="mt-2 text-sm text-slate-600">
                  This event has no upcoming shows. Its shows have all taken place, or none has been scheduled.
                </p>
              ) : (
                <ul className="mt-3 divide-y divide-slate-200 border-y border-slate-200">
                  {event.data.upcomingShows.map((show) => (
                    <li key={show.id}>
                      <Link
                        to={`/shows/${show.id}`}
                        className="flex items-center justify-between gap-4 px-1 py-3 hover:bg-slate-50"
                      >
                        <span className="text-slate-900">{formatShowTime(show.startsAt)}</span>
                        <span className="flex items-center gap-4 whitespace-nowrap text-sm">
                          <span className="text-slate-600">From {formatPrice(show.basePrice)}</span>
                          <span className="font-medium text-slate-900">Choose seats →</span>
                        </span>
                      </Link>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </article>
        )}
      </div>
    </main>
  )
}
