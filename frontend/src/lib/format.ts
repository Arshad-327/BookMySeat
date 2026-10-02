/**
 * Show times are rendered in Asia/Kolkata, explicitly - NOT in the browser's zone.
 *
 * The API serves instants in UTC and leaves the zone to the client. The confirmation
 * email (notification-service) renders in Asia/Kolkata, because that is where the venues
 * are. A page that used the viewer's own zone would show a different time from the email
 * to anybody outside India, for the same show. One zone, the venue's, in both places.
 */
const SHOW_TIME = new Intl.DateTimeFormat('en-IN', {
  timeZone: 'Asia/Kolkata',
  weekday: 'short',
  day: 'numeric',
  month: 'short',
  year: 'numeric',
  hour: 'numeric',
  minute: '2-digit',
  hour12: true,
})

/**
 * A show's start in the venue's zone: "Fri 9 Oct 2026, 6:30 pm IST".
 *
 * Assembled from the formatter's PARTS rather than taken from format(). The locale's own
 * string is "Fri, 9 Oct, 2026, 6:30 pm" - three commas, one of them between the month and
 * the year - and its punctuation is the locale data's to change between browser versions.
 * Taking the parts keeps the locale's words (the weekday and month names, am/pm) and puts
 * the punctuation here, where it is ours: one comma, between the date and the time.
 */
export function formatShowTime(instant: string): string {
  const parts = new Map(SHOW_TIME.formatToParts(new Date(instant)).map((part) => [part.type, part.value]))
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.get(type) ?? ''

  const date = `${part('weekday')} ${part('day')} ${part('month')} ${part('year')}`
  const time = `${part('hour')}:${part('minute')} ${part('dayPeriod')}`
  return `${date}, ${time} IST`
}

const RUPEES = new Intl.NumberFormat('en-IN', {
  style: 'currency',
  currency: 'INR',
  minimumFractionDigits: 0,
  maximumFractionDigits: 2,
})

/** Rupees, without ".00" on a whole amount. The API has no currency field; it is all INR. */
export function formatPrice(amount: number): string {
  return RUPEES.format(amount)
}

const CLOCK_TIME = new Intl.DateTimeFormat('en-IN', {
  timeZone: 'Asia/Kolkata',
  hour: 'numeric',
  minute: '2-digit',
  second: '2-digit',
  hour12: true,
})

/** A time of day to the second, in the same zone as show times: "6:52:10 pm IST". */
export function formatClockTime(instant: string): string {
  return `${CLOCK_TIME.format(new Date(instant))} IST`
}

/** "C4" from a row label and a seat number. */
export function seatLabel(rowLabel: string, seatNumber: number): string {
  return `${rowLabel}${seatNumber}`
}

/** "C4", "C4 and C5", "C4, C5 and C6". */
export function joinList(items: readonly string[]): string {
  if (items.length <= 1) {
    return items.join('')
  }
  return `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`
}
