import { api } from './http'
import type { EventSummaryResponse, PageResponse } from './types'

/**
 * GET /api/events. Public - no token needed, and the gateway does not look at one.
 * Only events with an upcoming show are listed, so an empty page means exactly that.
 */
export async function listEvents(): Promise<PageResponse<EventSummaryResponse>> {
  const response = await api.get<PageResponse<EventSummaryResponse>>('/api/events')
  return response.data
}
