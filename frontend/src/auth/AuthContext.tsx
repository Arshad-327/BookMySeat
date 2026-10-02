import { useQueryClient } from '@tanstack/react-query'
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'

import * as authApi from '../api/auth'
import { toApiError, type ApiError } from '../api/errors'
import { endSession, onSignedOut, startSession } from '../api/http'
import { restoreSession } from '../api/session'
import { getAccessToken, getRefreshToken } from '../api/tokenStore'
import type { UserResponse } from '../api/types'

/**
 * Who is signed in. The only client state in the app that is not server state.
 *
 * - `restoring`: start-up, before the stored refresh token has been tried.
 * - `signedIn` / `signedOut`: known.
 * - `unknown`: there IS a session - a refresh token in storage - but the server could not be
 *   asked whose. Deliberately not `signedOut`: the token was never REFUSED, it was never
 *   CHECKED, and telling the user they are logged out would be untrue.
 *
 *   Reached two ways, and the rule is the same for both: a 429, a 5xx or no answer at all
 *   means not checked; only a 401 means the token is dead.
 *     - at start-up, when the refresh or the /me after it cannot be completed;
 *     - at login, when the login succeeds and the /me after it does not.
 *   `recheck` is the way out.
 */
export type AuthState =
  | { status: 'restoring' }
  | { status: 'signedOut' }
  | { status: 'unknown'; error: ApiError }
  | { status: 'signedIn'; user: UserResponse }

interface AuthContextValue {
  auth: AuthState
  /**
   * Logs in and loads the user. Rejects with an ApiError only if the user is NOT logged in
   * afterwards - a wrong password is a 401. Resolves, leaving `auth` as `unknown`, when the
   * login worked and the user could not be loaded.
   */
  signIn: (email: string, password: string) => Promise<void>
  signOut: () => void
  /** From `unknown`: ask the server again who is signed in. */
  recheck: () => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

/** Queries whose answer belongs to one user. Dropped whenever the user changes. */
const PER_USER_QUERIES = ['bookings'] as const

export function AuthProvider({ children }: { children: ReactNode }) {
  const queryClient = useQueryClient()
  const [auth, setAuth] = useState<AuthState>({ status: 'restoring' })

  const forgetUserData = useCallback(() => {
    queryClient.removeQueries({ queryKey: PER_USER_QUERIES })
  }, [queryClient])

  // Start-up. StrictMode runs this twice in development; restoreSession is single-flight,
  // so the two runs share one refresh and one /me rather than racing on a rotating token.
  useEffect(() => {
    let cancelled = false
    restoreSession()
      .then((user) => {
        if (!cancelled) {
          setAuth(user ? { status: 'signedIn', user } : { status: 'signedOut' })
        }
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          setAuth({ status: 'unknown', error: toApiError(error) })
        }
      })
    return () => {
      cancelled = true
    }
  }, [])

  // The interceptor ends the session when the server refuses the refresh token. It lives
  // outside React, so this is how the UI finds out.
  useEffect(
    () =>
      onSignedOut(() => {
        forgetUserData()
        setAuth({ status: 'signedOut' })
      }),
    [forgetUserData],
  )

  /**
   * Logging in is TWO requests, and they fail differently.
   *
   *  1. POST /api/auth/login. If this fails, nobody is logged in: the error is thrown and
   *     the form shows it. A wrong password is here.
   *
   *  2. GET /api/auth/me, to learn who that was. By now the login HAS succeeded and both
   *     tokens are stored. If this request fails, the user is logged in and the page does
   *     not know as whom - which is `unknown`, not `signedOut`.
   *
   * This used to treat the two alike, and it was wrong in a way that only showed under a
   * rate limit: a 429 on /me straight after a successful login threw, the form said
   * "Could not log in", the header said signed out - and a valid refresh token was sitting
   * in storage the whole time. The session had not been REJECTED. It had not been CHECKED.
   * Same distinction as a dead backend at start-up, and now the same state.
   *
   * The one failure of /me that does mean "not logged in" is a 401: the server looked at
   * the token it issued a moment ago and refused it. Then the tokens are dropped and the
   * error is thrown like a failed login.
   */
  const signIn = useCallback(
    async (email: string, password: string) => {
      let tokens
      try {
        tokens = await authApi.login({ email, password })
      } catch (error) {
        throw toApiError(error)
      }
      startSession(tokens)
      forgetUserData()
      try {
        setAuth({ status: 'signedIn', user: await authApi.me() })
      } catch (caught) {
        const error = toApiError(caught)
        if (error.status === 401) {
          endSession()
          setAuth({ status: 'signedOut' })
          throw error
        }
        // A 429, a 5xx, or no answer. Logged in; not checked. NOT thrown: the login worked.
        setAuth({ status: 'unknown', error })
      }
    },
    [forgetUserData],
  )

  /**
   * The way out of `unknown` without reloading the page: ask again.
   *
   * With an access token in hand (login succeeded, /me did not) that is one GET /me. With
   * none (start-up restore could not reach the server) it is the whole restore: refresh,
   * then /me. Either way the three outcomes are the same three - a user, nobody, or still
   * unable to ask.
   */
  const recheck = useCallback(() => {
    setAuth({ status: 'restoring' })
    const asking = getAccessToken() ? authApi.me() : restoreSession()
    asking
      .then((user) => setAuth(user ? { status: 'signedIn', user } : { status: 'signedOut' }))
      .catch((error: unknown) => {
        // The interceptor ends the session itself if the refresh token was refused; the
        // listener above has then already set signedOut, and this must not overwrite it.
        if (getRefreshToken()) {
          setAuth({ status: 'unknown', error: toApiError(error) })
        }
      })
  }, [])

  const signOut = useCallback(() => {
    const refreshToken = getRefreshToken()
    // Locally first, so the UI is signed out at once and regardless of the network.
    endSession()
    forgetUserData()
    setAuth({ status: 'signedOut' })
    // Then tell the server, so the token is revoked there and not merely forgotten here.
    // Best effort: if this fails the token stays valid until it expires, which is the
    // situation the user would have been in had they simply closed the tab.
    if (refreshToken) {
      authApi.logout(refreshToken).catch(() => undefined)
    }
  }, [forgetUserData])

  const value = useMemo(() => ({ auth, signIn, signOut, recheck }), [auth, signIn, signOut, recheck])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext)
  if (!value) {
    throw new Error('useAuth must be used inside <AuthProvider>')
  }
  return value
}
