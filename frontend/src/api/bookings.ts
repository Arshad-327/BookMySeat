import { api } from './http'
import type { PageResponse } from './types'

/**
 * How many bookings the signed-in user has, in every status.
 *
 * GET /api/bookings?size=1 and read totalElements: one row is fetched and thrown away,
 * because the count is all that is wanted.
 *
 * This is the only call in the app so far that crosses the gateway's JWT filter. Browse
 * is public and /me is authenticated by auth-service itself, so without this nothing
 * would prove the gateway validates the token and injects X-User-Id - or exercise the
 * interceptor's main case, a 401 on a path outside /api/auth/.
 *
 * The content is typed `unknown` deliberately. No screen renders a booking yet; the
 * BookingResponse interface arrives with the first screen that does.
 */
export async function countMyBookings(): Promise<number> {
  const response = await api.get<PageResponse<unknown>>('/api/bookings', { params: { size: 1 } })
  return response.data.totalElements
}
