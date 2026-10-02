import { me } from './auth'
import { refreshOnce } from './http'
import { getRefreshToken } from './tokenStore'
import type { UserResponse } from './types'

let restoreInFlight: Promise<UserResponse | null> | null = null

/**
 * At start-up: who, if anyone, is signed in?
 *
 * The access token is held in memory only, so every page load begins without one. If
 * there is a refresh token in storage, it is exchanged for a new pair and /me is asked who
 * that is. Resolves with null when there is no session to restore or the server refused
 * the token; REJECTS when the answer could not be obtained at all (the gateway is down),
 * because "signed out" and "could not ask" are different facts.
 *
 * Single-flight, like the refresh underneath it and for the same reason: React 18
 * StrictMode runs the effect that calls this twice in development.
 */
export function restoreSession(): Promise<UserResponse | null> {
  if (restoreInFlight) {
    return restoreInFlight
  }
  if (!getRefreshToken()) {
    return Promise.resolve(null)
  }
  restoreInFlight = refreshOnce()
    .then(() => me())
    .catch((error: unknown) => {
      // refreshOnce has already ended the session if the server refused the token, in
      // which case storage is now empty and the honest answer is "nobody".
      if (!getRefreshToken()) {
        return null
      }
      throw error
    })
    .finally(() => {
      restoreInFlight = null
    })
  return restoreInFlight
}
