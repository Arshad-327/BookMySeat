import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'

import { countMyBookings } from '../api/bookings'
import { toApiError, type ApiError } from '../api/errors'
import { useAuth } from '../auth/AuthContext'

export function Header() {
  const { auth, signOut, recheck } = useAuth()

  return (
    <header className="border-b border-slate-200 bg-white">
      <div className="mx-auto flex max-w-5xl items-center justify-between gap-4 px-4 py-3">
        <Link to="/" className="text-lg font-semibold text-slate-900">
          BookMySeat
        </Link>

        <nav className="flex items-center gap-4 text-sm text-slate-700">
          {auth.status === 'restoring' && <span className="text-slate-400">Checking session…</span>}

          {/* A session exists and the server could not be asked about it. NOT "signed out":
              see AuthState. The reason is said, because "too many requests" and "nothing
              answered" call for different amounts of patience, and there is a way to ask
              again that does not involve reloading the page. */}
          {auth.status === 'unknown' && (
            <>
              <span data-testid="session-not-checked" className="text-amber-700">
                Session not checked: {notCheckedReason(auth.error)}
              </span>
              <button type="button" onClick={recheck} className="font-medium text-slate-900 underline">
                Check again
              </button>
            </>
          )}

          {auth.status === 'signedOut' && (
            <>
              <Link to="/login" className="hover:text-slate-900">
                Log in
              </Link>
              <Link to="/register" className="rounded bg-slate-900 px-3 py-1.5 font-medium text-white">
                Register
              </Link>
            </>
          )}

          {auth.status === 'signedIn' && (
            <>
              <MyBookingsCount userId={auth.user.id} />
              <span data-testid="signed-in-as" className="font-medium text-slate-900">
                {auth.user.fullName ?? auth.user.email}
              </span>
              <button type="button" onClick={signOut} className="text-slate-500 hover:text-slate-900">
                Log out
              </button>
            </>
          )}
        </nav>
      </div>
    </header>
  )
}

/**
 * "My bookings (N)". One line, and the reason the slice is a VERTICAL slice.
 *
 * Everything else on screen is either public (the event list) or authenticated by
 * auth-service itself (/api/auth/me). This is the one request that passes through the
 * gateway's JWT filter: the gateway validates the access token, sets X-User-Id from it,
 * and booking-service answers for that user. It is also the only request whose 401 takes
 * the interceptor's main branch - refresh, then retry.
 *
 * Not a link: there is no bookings page yet.
 */
function MyBookingsCount({ userId }: { userId: number }) {
  const count = useQuery({
    // The user id is in the key so one user's count can never be shown to the next.
    queryKey: ['bookings', 'count', userId],
    queryFn: countMyBookings,
  })

  if (count.isPending) {
    return <span className="text-slate-400">My bookings (…)</span>
  }
  if (count.isError) {
    return (
      <span className="text-amber-700" title={toApiError(count.error).message}>
        My bookings (unavailable)
      </span>
    )
  }
  return <span data-testid="my-bookings-count">My bookings ({count.data})</span>
}

function notCheckedReason(error: ApiError): string {
  if (error.kind === 'unreachable') {
    return 'the API did not answer'
  }
  if (error.status === 429) {
    return 'too many requests'
  }
  return `the server failed (HTTP ${error.status})`
}
