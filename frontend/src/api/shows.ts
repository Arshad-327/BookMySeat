import { api } from './http'
import type { SeatMapResponse } from './types'

/**
 * GET /api/shows/{id}/seats. Public, and polled - the gateway does not look at a token on
 * this path, so an expired one can never turn a poll into a 401.
 * 404 when there is no such show.
 */
export async function getSeatMap(showId: number): Promise<SeatMapResponse> {
  const response = await api.get<SeatMapResponse>(`/api/shows/${showId}/seats`)
  return response.data
}
