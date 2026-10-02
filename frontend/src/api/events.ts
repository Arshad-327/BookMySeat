import { api } from './http'
import type { EventDetailResponse, EventSummaryResponse, PageResponse } from './types'

/**
 * GET /api/events. Public - no token needed, and the gateway does not look at one.
 * Only events with an upcoming show are listed, so an empty page means exactly that.
 */
export async function listEvents(): Promise<PageResponse<EventSummaryResponse>> {
  const response = await api.get<PageResponse<EventSummaryResponse>>('/api/events')
  return response.data
}

/**
 * GET /api/events/{id}. Public. 404 when there is no such event; an event whose shows
 * have all happened is a 200 with an empty upcomingShows.
 */
export async function getEvent(id: number): Promise<EventDetailResponse> {
  const response = await api.get<EventDetailResponse>(`/api/events/${id}`)
  return response.data
}
