/**
 * Where the two tokens live. This file is the only code that touches either.
 *
 * ---------------------------------------------------------------------------------------
 * THE DECISION, AND WHAT IT COSTS
 * ---------------------------------------------------------------------------------------
 *   access token   IN MEMORY ONLY. A module variable. Gone on reload, never written to disk.
 *   refresh token  localStorage. Survives reload and browser restart.
 *
 * The access token is what every API call carries, so it is the one worth keeping out of
 * reach: script injected into the page cannot read a variable in a module closure the way
 * it can read storage. It is also short-lived - fifteen minutes.
 *
 * The refresh token has to survive a reload or every refresh of the page is a login. That
 * means storage, and that means:
 *
 *     A REFRESH TOKEN IN localStorage IS READABLE BY ANY XSS ON THIS ORIGIN.
 *
 * Any script that runs here - an injected one, or a compromised dependency - can read it
 * and use it to mint access tokens. That is the standard trade-off without httpOnly
 * cookies, and cookies were never available: auth-service returns the refresh token in a
 * JSON response body, and the gateway's CORS policy is allowCredentials: false.
 *
 * The mitigation is on the server, not here: the refresh token ROTATES. Every
 * POST /api/auth/refresh revokes the token it was given and issues a new one, so a stolen
 * token is good for one use and is dead the moment the real client refreshes - or kills
 * the real client's session, which is at least visible. (What the backend does NOT do is
 * treat a reused token as theft and revoke the whole family; that is review finding #10,
 * recorded and deferred.)
 *
 * Consequence for the app: a page reload starts with no access token, so start-up does
 * one refresh before it can say who is signed in. See restoreSession in ./session.ts.
 * ---------------------------------------------------------------------------------------
 */

const REFRESH_TOKEN_KEY = 'bookmyseat.refreshToken'

let accessToken: string | null = null

export function getAccessToken(): string | null {
  return accessToken
}

export function setAccessToken(token: string | null): void {
  accessToken = token
}

/**
 * Null when there is none, and also when storage cannot be read at all - a private
 * window with storage disabled throws on access. Signed out is the right answer to both.
 */
export function getRefreshToken(): string | null {
  try {
    return localStorage.getItem(REFRESH_TOKEN_KEY)
  } catch {
    return null
  }
}

export function setRefreshToken(token: string | null): void {
  try {
    if (token === null) {
      localStorage.removeItem(REFRESH_TOKEN_KEY)
    } else {
      localStorage.setItem(REFRESH_TOKEN_KEY, token)
    }
  } catch {
    // Storage unavailable. The session then lasts until the page is reloaded, which is
    // the most this browser will allow.
  }
}
