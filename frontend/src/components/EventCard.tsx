import { useState } from 'react'

import type { EventSummaryResponse } from '../api/types'
import { formatPrice, formatShowTime } from '../lib/format'

/**
 * One event on the browse page.
 *
 * The date and the price are rendered unconditionally. The API lists an event only if it
 * has an upcoming show, so both are always present - see EventSummaryResponse.
 *
 * Not a link yet: there is no event page in this slice.
 */
export function EventCard({ event }: { event: EventSummaryResponse }) {
  return (
    <article className="flex flex-col overflow-hidden rounded-lg border border-slate-200 bg-white">
      <Poster title={event.title} url={event.posterUrl} />
      <div className="flex flex-1 flex-col gap-1 p-4">
        <p className="text-xs font-medium uppercase tracking-wide text-slate-500">{event.category}</p>
        <h2 className="text-lg font-semibold text-slate-900">{event.title}</h2>
        <p className="text-sm text-slate-600">
          {event.venue.name}, {event.venue.city}
        </p>
        <div className="mt-auto flex items-end justify-between gap-3 pt-3">
          <p className="text-sm text-slate-700">
            <span className="block text-xs text-slate-500">Next show</span>
            {formatShowTime(event.nextShowStartsAt)}
          </p>
          {/* "From", never "cheapest": this is the lowest base price, not a seat price. */}
          <p className="whitespace-nowrap text-sm font-semibold text-slate-900">
            From {formatPrice(event.fromPrice)}
          </p>
        </div>
      </div>
    </article>
  )
}

/**
 * The poster, or a placeholder. A placeholder is needed in TWO cases, not one: the event
 * has no poster URL, or it has one that does not load - the seeded data points at a CDN
 * host that does not exist. Without the onError fallback that is a broken-image icon.
 */
function Poster({ title, url }: { title: string; url: string | null }) {
  const [failed, setFailed] = useState(false)

  if (!url || failed) {
    return (
      <div
        aria-hidden="true"
        className="flex h-40 items-center justify-center bg-slate-200 text-4xl font-semibold text-slate-400"
      >
        {title.charAt(0).toUpperCase()}
      </div>
    )
  }
  return <img src={url} alt="" onError={() => setFailed(true)} className="h-40 w-full object-cover" />
}
