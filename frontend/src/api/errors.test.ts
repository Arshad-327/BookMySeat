import { AxiosError, AxiosHeaders, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { describe, expect, it } from 'vitest'

import { ApiError, toApiError } from './errors'

/**
 * toApiError: how a failed request is classified. It has real branches - no response versus
 * a response, the standard error body versus anything else, a 429's Retry-After, and the
 * field that tells one 409 from another - and every screen's error handling hangs off them.
 *
 * =======================================================================================
 * WHAT IS NOT TESTED IN THIS DIRECTORY, ON PURPOSE: THE INTERCEPTOR (http.ts)
 * =======================================================================================
 * There is no http.test.ts, and that is a decision, not a gap.
 *
 * The interceptor's rules - refresh only on a 401 that carried a token and is not under
 * /api/auth/ (except /me), one refresh at a time, a dead refresh token ends the session -
 * were proven against the LIVE gateway, with the real modules bundled and run, by counting
 * rows in auth_db.refresh_tokens: three parallel requests with a dead access token produced
 * exactly one new row. Then again in a real browser, where a reload produced exactly one
 * POST /api/auth/refresh and the stored token changed.
 *
 * A unit test here would replace the gateway, auth-service and the database with a fake
 * that answers 401 when told to. It would pass. It would also be a weaker claim than the
 * one already made, wearing a test's clothes: it would show that the code calls the mock
 * the way the mock was written to be called. The thing that can actually be wrong - whether
 * rotation, the gateway's 401 and the single-flight agree with each other - is exactly
 * what a fake removes.
 *
 * What is tested instead is what CAN be pinned without a server: the pure decisions.
 */

function axiosError(status: number | null, data?: unknown, headers: Record<string, string> = {}): AxiosError {
  const config = { headers: new AxiosHeaders() } as InternalAxiosRequestConfig
  if (status === null) {
    return new AxiosError('Network Error', 'ERR_NETWORK', config, {})
  }
  const response: AxiosResponse = { status, statusText: '', data, headers, config }
  return new AxiosError(`Request failed with status code ${status}`, 'ERR_BAD_RESPONSE', config, {}, response)
}

const standardBody = (status: number, message: string) => ({
  timestamp: '2026-10-02T12:00:00Z',
  status,
  error: 'x',
  message,
  path: '/api/x',
})

describe('no response at all', () => {
  it('is "unreachable", with no status - the gateway is down, or the browser blocked the answer', () => {
    const error = toApiError(axiosError(null))
    expect(error.kind).toBe('unreachable')
    expect(error.status).toBeUndefined()
    expect(error.message).toBe('Network Error')
  })

  it('something that is not a request error at all is also "unreachable", never a crash', () => {
    expect(toApiError(new Error('boom')).kind).toBe('unreachable')
    expect(toApiError(new Error('boom')).message).toBe('boom')
    expect(toApiError('a string').message).toBe('Unknown error')
  })
})

describe('a response with an error status', () => {
  it('is "http", with the status and the SERVER\'S message when the body is the standard shape', () => {
    const error = toApiError(axiosError(409, standardBody(409, 'Email is already registered')))
    expect(error.kind).toBe('http')
    expect(error.status).toBe(409)
    expect(error.message).toBe('Email is already registered')
  })

  it.each([
    ['an HTML page', '<html>Bad Gateway</html>'],
    ['nothing', undefined],
    ['JSON of another shape', { detail: 'nope' }],
    ['a message without a status', { message: 'nope' }],
  ])('falls back to a message built from the status when the body is %s', (_, data) => {
    expect(toApiError(axiosError(502, data)).message).toBe('Request failed with status 502')
  })

  it('an ApiError is passed through as itself', () => {
    const original = new ApiError('http', 'already classified', 400)
    expect(toApiError(original)).toBe(original)
  })
})

describe('client errors and the retry policy that reads them', () => {
  // Three statuses, each there for a reason, and each shown red by its own mutation: the
  // two edges of the range, and 429 - the one a well-meaning change would carve out
  // ("surely a rate-limited request should be retried"), which is exactly how a client
  // keeps itself rate-limited. 401, 404 and 409 used to be listed here too. No plausible
  // break reddened them that did not also redden one of these, so they were decoration
  // and were removed rather than left to look like coverage.
  it.each([400, 429, 499])('a %i is a client error: the retry policy will not repeat it', (status) => {
    expect(toApiError(axiosError(status)).isClientError).toBe(true)
  })

  it('a 500 is not: the first status the retry policy WILL repeat', () => {
    expect(toApiError(axiosError(500)).isClientError).toBe(false)
  })

  it('no response is not a client error either, so it IS retried', () => {
    expect(toApiError(axiosError(null)).isClientError).toBe(false)
  })
})

describe("a 429's Retry-After", () => {
  it('is read as whole seconds', () => {
    expect(toApiError(axiosError(429, standardBody(429, 'slow down'), { 'retry-after': '3' })).retryAfterSeconds).toBe(3)
  })

  it.each([
    ['is missing', {}],
    ['is zero', { 'retry-after': '0' }],
    ['is negative', { 'retry-after': '-4' }],
    ['is not a number', { 'retry-after': 'soon' }],
  ])('is one second when the header %s - a 429 always means "wait", never "retry now"', (_, headers) => {
    expect(toApiError(axiosError(429, undefined, headers)).retryAfterSeconds).toBe(1)
  })

  it('is not read off anything that is not a 429', () => {
    expect(toApiError(axiosError(503, undefined, { 'retry-after': '30' })).retryAfterSeconds).toBeUndefined()
  })
})

describe('conflictingSeatIds: the field whose PRESENCE tells one 409 from another', () => {
  it('is the list, when the body has one', () => {
    const body = { ...standardBody(409, 'Seats are currently held by another booking: [24, 25]'), conflictingSeatIds: [24, 25] }
    expect(toApiError(axiosError(409, body)).conflictingSeatIds).toEqual([24, 25])
  })

  it('is UNDEFINED when the body has no such field - a seat already sold, a show already started', () => {
    expect(toApiError(axiosError(409, standardBody(409, 'Seats not available: [43]'))).conflictingSeatIds).toBeUndefined()
  })

  it('an EMPTY list is still a list: present, not undefined', () => {
    // The branch in the seat-map page is "is the field there", not "is it non-empty".
    const body = { ...standardBody(409, 'x'), conflictingSeatIds: [] }
    expect(toApiError(axiosError(409, body)).conflictingSeatIds).toEqual([])
  })

  it.each([
    ['a string', '24,25'],
    ['a list with something that is not a number in it', [24, '25']],
    ['null', null],
  ])('is undefined when the field is %s, rather than passing rubbish on as seat ids', (_, value) => {
    const body = { ...standardBody(409, 'x'), conflictingSeatIds: value }
    expect(toApiError(axiosError(409, body)).conflictingSeatIds).toBeUndefined()
  })
})
