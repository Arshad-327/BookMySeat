import { useQueryClient } from '@tanstack/react-query'
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'

import * as authApi from '../api/auth'
import { toApiError, type ApiError } from '../api/errors'
import { endSession, onSignedOut, startSession } from '../api/http'
import { restoreSession } from '../api/session'
import { getRefreshToken } from '../api/tokenStore'
import type { UserResponse } from '../api/types'

/**
 * Who is signed in. The only client state in the app that is not server state.
 *
 * - `restoring`: start-up, before the stored refresh token has been tried.
 * - `signedIn` / `signedOut`: known.
 * - `unknown`: there IS a refresh token, but the server could not be asked about it (the
 *   gateway is down). Deliberately not `signedOut`: the token was never refused and is
 *   still in storage, and telling the user they are logged out would be untrue.
 */
export type AuthState =
  | { status: 'restoring' }
  | { status: 'signedOut' }
  | { status: 'unknown'; error: ApiError }
  | { status: 'signedIn'; user: UserResponse }

interface AuthContextValue {
  auth: AuthState
  /** Logs in and loads the user. Rejects with an ApiError; a wrong password is a 401. */
  signIn: (email: string, password: string) => Promise<void>
  signOut: () => void
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

  const signIn = useCallback(
    async (email: string, password: string) => {
      try {
        startSession(await authApi.login({ email, password }))
        const user = await authApi.me()
        forgetUserData()
        setAuth({ status: 'signedIn', user })
      } catch (error) {
        throw toApiError(error)
      }
    },
    [forgetUserData],
  )

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

  const value = useMemo(() => ({ auth, signIn, signOut }), [auth, signIn, signOut])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext)
  if (!value) {
    throw new Error('useAuth must be used inside <AuthProvider>')
  }
  return value
}
