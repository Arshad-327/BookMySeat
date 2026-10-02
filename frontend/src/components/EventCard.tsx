import { useState } from 'react'
import { Link } from 'react-router-dom'

import type { EventSummaryResponse } from '../api/types'
import { formatPrice, formatShowTime } from '../lib/format'

/**
 * One event on the browse page.
 *
 * The date and the price are rendered unconditionally. The API lists an event only if it
 * has an upcoming show, so both are always present - see EventSummaryResponse.
 *
 * The whole card is one link to the event page. One link, not a link around each piece: a
 * screen reader then announces the card once, and it reads the heading as the link text.
 */
export function EventCard({ event }: { event: EventSummaryResponse }) {
  return (
    <article className="h-full overflow-hidden rounded-lg border border-slate-200 bg-white transition hover:border-slate-400 focus-within:border-slate-900">
      <Link to={`/events/${event.id}`} className="flex h-full flex-col focus:outline-none">
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
      </Link>
    </article>
  )
}

/**
 * The poster, or a placeholder. A placeholder is needed in TWO cases, not one: the event
 * has no poster URL, or it has one that does not load. Neither seeded event has a poster, so
 * the placeholder is what the demo shows; without the onError fallback a dead URL would be
 * a broken-image icon.
 */
export function Poster({ title, url }: { title: string; url: string | null }) {
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
