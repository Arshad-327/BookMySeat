import { useState, type FormEvent } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'

import { toApiError, type ApiError } from '../api/errors'
import { useAuth } from '../auth/AuthContext'
import { ErrorNotice } from '../components/ErrorNotice'
import { destinationAfterSignIn } from '../lib/returnToShow'

/**
 * Log in. Two controlled inputs and no form library: there is nothing here a library would
 * do that the browser's own `required` and the server's own messages do not.
 *
 * A wrong password is a 401 from auth-service. The interceptor leaves it alone - the path
 * is under /api/auth/ - so it arrives here as an ordinary error with the server's message.
 */
export function LoginPage() {
  const { auth, signIn } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  // The show the user came from, if they came from one; otherwise the browse page. Used by
  // BOTH exits below - the redirect for someone already signed in and the navigate after a
  // successful submit - because signing in flips the first one on in the same breath as
  // the second runs, and two different destinations there would be a race.
  const destination = destinationAfterSignIn(location.state)
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<ApiError | null>(null)

  if (auth.status === 'signedIn') {
    return <Navigate to={destination} replace />
  }

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setSubmitting(true)
    setError(null)
    try {
      await signIn(email, password)
      // Never a destination read from the URL: react-router 6 has an open-redirect
      // advisory for navigation targets built from user input. See lib/returnToShow.
      navigate(destination, { replace: true })
    } catch (caught) {
      setError(toApiError(caught))
      setSubmitting(false)
    }
  }

  return (
    <main className="mx-auto max-w-sm px-4 py-10">
      <h1 className="text-2xl font-semibold text-slate-900">Log in</h1>

      <form onSubmit={(event) => void onSubmit(event)} className="mt-6 flex flex-col gap-4">
        <label className="flex flex-col gap-1 text-sm font-medium text-slate-700">
          Email
          <input
            type="email"
            name="email"
            autoComplete="email"
            required
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            className="rounded border border-slate-300 px-3 py-2 font-normal text-slate-900"
          />
        </label>

        <label className="flex flex-col gap-1 text-sm font-medium text-slate-700">
          Password
          <input
            type="password"
            name="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            className="rounded border border-slate-300 px-3 py-2 font-normal text-slate-900"
          />
        </label>

        {error && <ErrorNotice action="log in" error={error} />}

        <button
          type="submit"
          disabled={submitting}
          className="rounded bg-slate-900 px-4 py-2 font-medium text-white disabled:opacity-50"
        >
          {submitting ? 'Logging in…' : 'Log in'}
        </button>
      </form>

      <p className="mt-6 text-sm text-slate-600">
        No account?{' '}
        <Link to="/register" state={location.state} className="font-medium text-slate-900 underline">
          Register
        </Link>
      </p>
    </main>
  )
}
