import { useQuery } from '@tanstack/react-query'

import { toApiError } from '../api/errors'
import { listEvents } from '../api/events'
import { ErrorNotice } from '../components/ErrorNotice'
import { EventCard } from '../components/EventCard'

/**
 * The first screen: every event with an upcoming show.
 *
 * THREE OUTCOMES THAT MUST NOT LOOK ALIKE.
 *
 *  - The request failed. ErrorNotice says how, and "nothing answered" is said outright.
 *  - The request succeeded and the list is EMPTY. That is a real answer, and it has one
 *    likely cause in development worth naming: the demo data's shows are 7, 14 and 21 days
 *    out, and the API lists only events with an upcoming show, so a database seeded more
 *    than three weeks ago lists nothing.
 *  - There are events.
 *
 * An empty grid for the first two would be the same blank page for a dead backend, a
 * stale database and a genuinely empty catalogue.
 */
export function BrowsePage() {
  const events = useQuery({ queryKey: ['events'], queryFn: listEvents })

  return (
    <main className="mx-auto max-w-5xl px-4 py-8">
      <h1 className="text-2xl font-semibold text-slate-900">Events</h1>

      <div className="mt-6">
        {events.isPending && <p className="text-slate-500">Loading events…</p>}

        {events.isError && (
          <ErrorNotice action="load events" error={toApiError(events.error)} onRetry={() => void events.refetch()} />
        )}

        {events.isSuccess && events.data.content.length === 0 && (
          <div data-testid="no-events" className="rounded-md border border-slate-200 bg-white p-6 text-slate-700">
            <p className="font-medium text-slate-900">No events with an upcoming show.</p>
            <p className="mt-1 text-sm">
              The API answered, and its list is empty. On a development database this usually means the seeded
              shows have all passed: run <code className="rounded bg-slate-100 px-1">load-tests/reset-fixtures.sh</code>{' '}
              to re-seed.
            </p>
          </div>
        )}

        {events.isSuccess && events.data.content.length > 0 && (
          <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {events.data.content.map((event) => (
              <li key={event.id}>
                <EventCard event={event} />
              </li>
            ))}
          </ul>
        )}
      </div>
    </main>
  )
}
