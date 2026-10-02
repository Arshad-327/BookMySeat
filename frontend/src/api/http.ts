import axios, { AxiosHeaders, type InternalAxiosRequestConfig } from 'axios'

import { API_BASE_URL } from '../config'
import { getAccessToken, getRefreshToken, setAccessToken, setRefreshToken } from './tokenStore'
import type { AuthResponse } from './types'

/**
 * The HTTP client, and the ONE interceptor in this app.
 *
 * =======================================================================================
 * WHEN A 401 MEANS "THE ACCESS TOKEN EXPIRED" - and when it does not
 * =======================================================================================
 * The usual React pattern treats every 401 as an expired session: refresh, retry, and on
 * failure log the user out. Against this backend that is wrong in three ways, so the
 * interceptor refreshes and retries ONLY when all of these are true:
 *
 *   1. The response is a 401.
 *
 *   2. THE REQUEST CARRIED AN Authorization HEADER. A 401 for a request that sent no token
 *      is "you are not signed in", not "your token expired". There is nothing to refresh.
 *
 *   3. THE PATH IS NOT UNDER /api/auth/ - EXCEPT /api/auth/me. auth-service answers 401
 *      for things that have nothing to do with the access token:
 *        - POST /api/auth/login with a wrong password. A naive interceptor turns a typo'd
 *          password into a refresh attempt.
 *        - POST /api/auth/refresh with a dead refresh token. Refreshing in answer to a
 *          failed refresh is a loop.
 *        - any mistyped path under /api/auth/, which Spring Security answers with 401
 *          rather than 404. A typo'd URL must not log anybody out.
 *      /api/auth/me is the exception because it is the one endpoint under that prefix
 *      that IS authenticated by the access token.
 *
 *   4. The request has not already been retried. One refresh, one retry. A second 401
 *      means the new token is not good enough either, and that is the caller's to see.
 *
 * Nothing here matches on an error MESSAGE. The gateway and auth-service word their 401s
 * differently, and wording is not a contract.
 *
 * =======================================================================================
 * THE REFRESH IS SINGLE-FLIGHT
 * =======================================================================================
 * Refresh tokens ROTATE: the one presented is revoked and a new one issued. So two
 * refreshes started from the same token cannot both succeed - the second presents a token
 * the first has just revoked, gets a 401, and the user is logged out for having two
 * requests in the air at once.
 *
 * That is not an edge case. A page that fires three queries when the access token has
 * expired gets three 401s within milliseconds. And in development, React 18 StrictMode
 * runs every effect twice, so the start-up refresh itself is called twice on every reload.
 *
 * So there is ONE refresh at a time: `refreshInFlight` below, a module-level promise. The
 * first caller starts the request and stores the promise; everyone who arrives while it is
 * pending awaits that same promise; it is cleared when it settles. A module variable and
 * not React state, because the interceptor is not inside React and the two StrictMode
 * effect runs are the same module.
 *
 * =======================================================================================
 * ACCEPTED, NOT SOLVED: TWO TABS
 * =======================================================================================
 * The single-flight is per page. Two tabs share one refresh token in localStorage and
 * each has its own module, its own promise. If both refresh at the same moment, one wins
 * and the other presents a revoked token and is signed out. Coordinating them would need
 * a lock across tabs (BroadcastChannel, the Web Locks API). That is deliberately NOT done:
 * the failure is a re-login in one tab, it needs two tabs to cross the same fifteen-minute
 * boundary within the same few milliseconds, and the fix is more moving parts than the
 * problem. Recorded in CLAUDE.md under "Not built".
 */

/** Every API call goes through this. Carries the access token once there is one. */
export const api = axios.create({ baseURL: API_BASE_URL })

/**
 * For POST /api/auth/refresh and nothing else. No interceptor and no Authorization
 * header: the refresh token in the body is the credential, and a refresh that could
 * itself be intercepted and retried is how a loop starts.
 */
const bare = axios.create({ baseURL: API_BASE_URL })

declare module 'axios' {
  interface AxiosRequestConfig {
    /** Set by the interceptor on the one retry it makes, so it never makes a second. */
    retriedAfterRefresh?: boolean
  }
}

// ---------------------------------------------------------------------------------------
// The session: both tokens, set and cleared together.
// ---------------------------------------------------------------------------------------

type SignedOutListener = () => void
const signedOutListeners = new Set<SignedOutListener>()

/** Called when the session ends because the server refused the refresh token. */
export function onSignedOut(listener: SignedOutListener): () => void {
  signedOutListeners.add(listener)
  return () => {
    signedOutListeners.delete(listener)
  }
}

/**
 * Stores both tokens and puts the access token on every later request.
 *
 * The header is set as an axios DEFAULT rather than by a request interceptor. That is what
 * keeps this file to one interceptor, and it means "did this request carry a token" can be
 * read straight off the failed request's own headers.
 */
export function startSession(tokens: AuthResponse): void {
  setAccessToken(tokens.accessToken)
  setRefreshToken(tokens.refreshToken)
  api.defaults.headers.common.Authorization = `Bearer ${tokens.accessToken}`
}

/** Forgets both tokens. Does not call the server - see logout in ./auth.ts for that. */
export function endSession(): void {
  setAccessToken(null)
  setRefreshToken(null)
  delete api.defaults.headers.common.Authorization
}

// ---------------------------------------------------------------------------------------
// The single-flight refresh.
// ---------------------------------------------------------------------------------------

let refreshInFlight: Promise<string> | null = null

/**
 * Exchanges the stored refresh token for a new pair and resolves with the new access
 * token. However many callers ask while one exchange is pending, ONE request is made.
 *
 * On failure, what happens to the session depends on what failed:
 *  - a 401 means the server looked at the refresh token and refused it. It is dead - used,
 *    revoked or expired - and keeping it would only repeat the refusal. The session ends
 *    and the signed-out listeners are told.
 *  - anything else (the gateway unreachable, a 429, a 5xx) means the token was never
 *    judged. It is KEPT. Logging somebody out because the network blinked would turn every
 *    outage into a mass sign-out.
 */
export function refreshOnce(): Promise<string> {
  if (refreshInFlight) {
    return refreshInFlight
  }
  const refreshToken = getRefreshToken()
  if (!refreshToken) {
    return Promise.reject(new Error('No refresh token: not signed in'))
  }

  refreshInFlight = bare
    .post<AuthResponse>('/api/auth/refresh', { refreshToken })
    .then((response) => {
      startSession(response.data)
      return response.data.accessToken
    })
    .catch((error: unknown) => {
      if (axios.isAxiosError(error) && error.response?.status === 401) {
        endSession()
        signedOutListeners.forEach((listener) => listener())
      }
      throw error
    })
    .finally(() => {
      refreshInFlight = null
    })
  return refreshInFlight
}

// ---------------------------------------------------------------------------------------
// The interceptor.
// ---------------------------------------------------------------------------------------

/** The path of a request, without origin or query string. Requests here are always relative. */
function pathOf(config: InternalAxiosRequestConfig): string {
  const url = config.url ?? ''
  const withoutOrigin = url.startsWith('http') ? new URL(url).pathname : url
  return withoutOrigin.split('?')[0] ?? ''
}

/** Rule 3: under /api/auth/ only /me is authenticated by the access token. */
function isAccessTokenProtected(path: string): boolean {
  return !path.startsWith('/api/auth/') || path === '/api/auth/me'
}

api.interceptors.response.use(undefined, async (error: unknown) => {
  if (!axios.isAxiosError(error) || !error.config || error.response?.status !== 401) {
    throw error
  }
  const config = error.config
  const sent = AxiosHeaders.from(config.headers).get('Authorization')

  if (typeof sent !== 'string' || !isAccessTokenProtected(pathOf(config)) || config.retriedAfterRefresh) {
    throw error
  }

  // If the token that was refused is not the one held now, a refresh has ALREADY happened
  // since this request left - it was in the air while another request's 401 was being
  // handled. Retry with the current token. Refreshing again would rotate the refresh
  // token a second time for nothing.
  const current = getAccessToken()
  const accessToken = current !== null && sent !== `Bearer ${current}` ? current : await refreshOnce()

  config.retriedAfterRefresh = true
  config.headers.set('Authorization', `Bearer ${accessToken}`)
  return api.request(config)
})
