import { useState, type FormEvent } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'

import * as authApi from '../api/auth'
import { toApiError, type ApiError } from '../api/errors'
import { useAuth } from '../auth/AuthContext'
import { ErrorNotice } from '../components/ErrorNotice'
import { destinationAfterSignIn } from '../lib/returnToShow'

/**
 * Register, then sign in.
 *
 * Two calls, because POST /api/auth/register returns the new user and NO tokens: the
 * account is created by the first and the session by a login that follows it.
 *
 * The rules are the server's. `minLength` and `maxLength` below mirror RegisterRequest so
 * the browser catches the obvious cases without a request, but the server's 400 is the
 * authority and its message is shown as it comes - as is the 409 for an email already
 * registered.
 */
export function RegisterPage() {
  const { auth, signIn } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  // The show the user came from, if they came from one; otherwise the browse page. Used by
  // BOTH exits below - the redirect for someone already signed in and the navigate after a
  // successful submit - because signing in flips the first one on in the same breath as
  // the second runs, and two different destinations there would be a race.
  const destination = destinationAfterSignIn(location.state)
  const [fullName, setFullName] = useState('')
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
      const name = fullName.trim()
      await authApi.register({ email, password, ...(name ? { fullName: name } : {}) })
      await signIn(email, password)
      navigate(destination, { replace: true })
    } catch (caught) {
      setError(toApiError(caught))
      setSubmitting(false)
    }
  }

  return (
    <main className="mx-auto max-w-sm px-4 py-10">
      <h1 className="text-2xl font-semibold text-slate-900">Create an account</h1>

      <form onSubmit={(event) => void onSubmit(event)} className="mt-6 flex flex-col gap-4">
        <label className="flex flex-col gap-1 text-sm font-medium text-slate-700">
          Name <span className="font-normal text-slate-500">(optional)</span>
          <input
            type="text"
            name="fullName"
            autoComplete="name"
            maxLength={120}
            value={fullName}
            onChange={(event) => setFullName(event.target.value)}
            className="rounded border border-slate-300 px-3 py-2 font-normal text-slate-900"
          />
        </label>

        <label className="flex flex-col gap-1 text-sm font-medium text-slate-700">
          Email
          <input
            type="email"
            name="email"
            autoComplete="email"
            required
            maxLength={255}
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            className="rounded border border-slate-300 px-3 py-2 font-normal text-slate-900"
          />
        </label>

        <label className="flex flex-col gap-1 text-sm font-medium text-slate-700">
          Password <span className="font-normal text-slate-500">(at least 8 characters)</span>
          <input
            type="password"
            name="password"
            autoComplete="new-password"
            required
            minLength={8}
            maxLength={100}
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            className="rounded border border-slate-300 px-3 py-2 font-normal text-slate-900"
          />
        </label>

        {error && <ErrorNotice action="create the account" error={error} />}

        <button
          type="submit"
          disabled={submitting}
          className="rounded bg-slate-900 px-4 py-2 font-medium text-white disabled:opacity-50"
        >
          {submitting ? 'Creating account…' : 'Register'}
        </button>
      </form>

      <p className="mt-6 text-sm text-slate-600">
        Already registered?{' '}
        <Link to="/login" state={location.state} className="font-medium text-slate-900 underline">
          Log in
        </Link>
      </p>
    </main>
  )
}
