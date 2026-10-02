import { api } from './http'
import type { BookingResponse, BookingStatus, CreateBookingRequest, PageResponse } from './types'

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
 * The content is typed `unknown` deliberately: the count is all this reads.
 */
export async function countMyBookings(): Promise<number> {
  const response = await api.get<PageResponse<unknown>>('/api/bookings', { params: { size: 1 } })
  return response.data.totalElements
}

/**
 * POST /api/bookings/hold. Takes an exclusive ten-minute hold on every seat, or on none.
 *
 *  201  held: a PENDING booking.
 *  200  a replay of this Idempotency-Key: the booking the first attempt made, unchanged.
 *  409  with conflictingSeatIds - other bookings hold those seats; nothing was held.
 *  409  without it - a seat is already sold, or the show has started.
 *  503  Redis or event-service is down; nothing was held.
 *
 * @param idempotencyKey a UUID naming this attempt. See SelectionState.idempotencyKey for
 *                       when it changes and when it must not.
 */
export async function holdSeats(request: CreateBookingRequest, idempotencyKey: string): Promise<BookingResponse> {
  const response = await api.post<BookingResponse>('/api/bookings/hold', request, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
  return response.data
}

/**
 * The signed-in user's PENDING bookings: GET /api/bookings?status=PENDING&size=100.
 *
 * For the seat map, which uses them to draw the user's own held seats as theirs. Those
 * seats read AVAILABLE in the seat map - holds are not in it - so without this a user who
 * came back to a show would be offered their own seats and told, on clicking, that someone
 * else holds them.
 *
 * One page of 100, the API's cap. A user with more than a hundred live holds is not a case
 * this needs to be right about.
 */
export async function listMyPendingBookings(): Promise<BookingResponse[]> {
  const response = await api.get<PageResponse<BookingResponse>>('/api/bookings', {
    params: { status: 'PENDING', size: 100 },
  })
  return response.data.content
}

/** GET /api/bookings/{id}. 404 when there is no such booking OR it is somebody else's. */
export async function getBooking(id: number): Promise<BookingResponse> {
  const response = await api.get<BookingResponse>(`/api/bookings/${id}`)
  return response.data
}

/**
 * POST /api/bookings/{id}/confirm. Marks the seats sold and the booking CONFIRMED.
 *
 *  200  confirmed - or, for a retry with the SAME key and the same booking, the booking
 *       that the first attempt confirmed.
 *  409  not PENDING, expired, the hold was lost, a seat was sold, or the show has started.
 *       Told apart only by the message, so the caller reads the booking back instead.
 *  503  "The booking could not be confirmed, please retry" - deliberately not "nothing
 *       happened": the seats may have been marked before the answer was lost.
 *
 * @param idempotencyKey optional in the API, always sent here. A confirm key lives in its
 *                       own namespace: it is not the key the hold was created with.
 */
export async function confirmBooking(id: number, idempotencyKey: string): Promise<BookingResponse> {
  const response = await api.post<BookingResponse>(`/api/bookings/${id}/confirm`, undefined, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
  return response.data
}

/**
 * DELETE /api/bookings/{id}. Cancels a PENDING booking and releases its seats before it
 * answers. 200 with the cancelled booking (nothing is deleted); 409 if it is not PENDING;
 * 503 if event-service is down, in which case the booking is still PENDING.
 */
export async function cancelBooking(id: number): Promise<BookingResponse> {
  const response = await api.delete<BookingResponse>(`/api/bookings/${id}`)
  return response.data
}

/** The page size the list asks for. Also the API's default; said out loud so it is a choice. */
export const BOOKINGS_PAGE_SIZE = 20

/**
 * One page of the signed-in user's bookings in the given statuses, newest first:
 * GET /api/bookings?status=A&status=B&page=N&size=20.
 *
 * AT LEAST ONE STATUS, enforced by the type: the parameter is never left off. The API
 * returns every status when it is absent, which is the one thing a list of bookings must
 * not ask for - see BookingsPage.
 *
 * The query string is built by hand. Axios's default for an array is `status[]=A&status[]=B`,
 * which Spring does not read as a repeated `status`; URLSearchParams.append writes the
 * plain repeated form the API documents.
 */
export async function listMyBookings(
  statuses: readonly [BookingStatus, ...BookingStatus[]],
  page: number,
): Promise<PageResponse<BookingResponse>> {
  const params = new URLSearchParams()
  statuses.forEach((status) => params.append('status', status))
  params.append('page', String(page))
  params.append('size', String(BOOKINGS_PAGE_SIZE))
  const response = await api.get<PageResponse<BookingResponse>>(`/api/bookings?${params.toString()}`)
  return response.data
}
