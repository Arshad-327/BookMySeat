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

/** A show's start, in the venue's zone, e.g. "Sat, 10 Oct 2026, 12:00 am IST". */
export function formatShowTime(instant: string): string {
  return `${SHOW_TIME.format(new Date(instant))} IST`
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
