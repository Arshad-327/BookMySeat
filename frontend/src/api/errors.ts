import axios from 'axios'

import type { ErrorResponse } from './types'

/**
 * What went wrong with a request, sorted by what the user can do about it.
 *
 * - `unreachable`: no HTTP response at all. The gateway is not running, the URL is wrong,
 *   or the browser refused the response (CORS). Nothing on the server said anything.
 * - `http`: the gateway or a service answered with an error status.
 *
 * The distinction is the point. A browse page that renders an empty grid for a dead
 * backend is telling the user "there are no events", which is a different fact from
 * "nothing answered" and sends them looking in the wrong place.
 */
export type ApiErrorKind = 'unreachable' | 'http'

export class ApiError extends Error {
  readonly kind: ApiErrorKind
  /** The HTTP status. Undefined exactly when kind is `unreachable`. */
  readonly status: number | undefined
  /** Seconds to wait, from a 429's Retry-After header. Undefined on anything else. */
  readonly retryAfterSeconds: number | undefined

  constructor(kind: ApiErrorKind, message: string, status?: number, retryAfterSeconds?: number) {
    super(message)
    this.name = 'ApiError'
    this.kind = kind
    this.status = status
    this.retryAfterSeconds = retryAfterSeconds
  }

  /** A 4xx: the request was understood and refused. Sending it again changes nothing. */
  get isClientError(): boolean {
    return this.status !== undefined && this.status >= 400 && this.status < 500
  }
}

function isErrorResponse(body: unknown): body is ErrorResponse {
  return (
    typeof body === 'object' &&
    body !== null &&
    typeof (body as Record<string, unknown>).message === 'string' &&
    typeof (body as Record<string, unknown>).status === 'number'
  )
}

/**
 * Turns whatever a request threw into an ApiError. Never throws.
 *
 * The server's own `message` is used when the body is the standard error shape - every
 * service writes those for a person to read ("Email is already registered"). Anything
 * else gets a message built from the status.
 */
export function toApiError(error: unknown): ApiError {
  if (error instanceof ApiError) {
    return error
  }
  if (axios.isAxiosError(error)) {
    const response = error.response
    if (!response) {
      return new ApiError('unreachable', error.message || 'No response from the server')
    }
    const message = isErrorResponse(response.data)
      ? response.data.message
      : `Request failed with status ${response.status}`

    // Retry-After is readable here only because the gateway lists it in its CORS
    // exposedHeaders. Without that, a browser hides it from script and this is undefined.
    let retryAfterSeconds: number | undefined
    if (response.status === 429) {
      const header: unknown = response.headers['retry-after']
      const parsed = typeof header === 'string' ? Number.parseInt(header, 10) : Number.NaN
      retryAfterSeconds = Number.isFinite(parsed) && parsed > 0 ? parsed : 1
    }
    return new ApiError('http', message, response.status, retryAfterSeconds)
  }
  return new ApiError('unreachable', error instanceof Error ? error.message : 'Unknown error')
}
