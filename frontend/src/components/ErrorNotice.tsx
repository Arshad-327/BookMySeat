import { useEffect, useState } from 'react'

import type { ApiError } from '../api/errors'
import { API_BASE_URL } from '../config'

interface ErrorNoticeProps {
  /** What was being attempted, to finish the sentence "Could not ...". */
  action: string
  error: ApiError
  /** Shown as a button when given. Disabled while a 429's Retry-After is still running. */
  onRetry?: () => void
}

/**
 * One place that decides how a failed request is explained, so every screen says the same
 * thing about the same failure.
 *
 * It tells four situations apart, because the user does something different about each:
 *
 *  - NOTHING ANSWERED. The gateway is not running, or the browser blocked the response.
 *    Said in so many words, with the URL that was tried - a dead backend must never look
 *    like an empty result.
 *  - RATE LIMITED (429). Not retried automatically. The gateway's Retry-After is counted
 *    down and the retry button stays disabled until it has passed, so the retry that
 *    follows is one that can succeed.
 *  - THE SERVER FAILED (5xx). The gateway is up; something behind it is not.
 *  - REFUSED (any other 4xx). The server's own message, which is written to be read.
 */
export function ErrorNotice({ action, error, onRetry }: ErrorNoticeProps) {
  const secondsLeft = useCountdown(error.retryAfterSeconds, error)

  let heading: string
  let detail: string
  if (error.kind === 'unreachable') {
    heading = `Could not ${action}: nothing answered at ${API_BASE_URL}`
    detail =
      'The API gateway is not running, or the browser blocked its response. ' +
      'This is a connection failure, not an empty result.'
  } else if (error.status === 429) {
    heading = `Could not ${action}: too many requests`
    detail =
      secondsLeft > 0
        ? `The rate limit was reached. Try again in ${secondsLeft} second${secondsLeft === 1 ? '' : 's'}.`
        : 'The rate limit was reached. You can try again now.'
  } else if (error.status !== undefined && error.status >= 500) {
    heading = `Could not ${action}: the server failed (HTTP ${error.status})`
    detail = `The gateway answered, but the service behind it did not. ${error.message}`
  } else {
    heading = `Could not ${action}`
    detail = error.message
  }

  return (
    <div role="alert" className="rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-900">
      <p className="font-medium">{heading}</p>
      <p className="mt-1">{detail}</p>
      {onRetry && (
        <button
          type="button"
          onClick={onRetry}
          disabled={secondsLeft > 0}
          className="mt-3 rounded bg-red-700 px-3 py-1.5 font-medium text-white disabled:cursor-not-allowed disabled:opacity-50"
        >
          Try again
        </button>
      )}
    </div>
  )
}

/** Counts down from `seconds` once per error. Zero when there is nothing to wait for. */
function useCountdown(seconds: number | undefined, resetKey: unknown): number {
  const [left, setLeft] = useState(seconds ?? 0)

  useEffect(() => {
    setLeft(seconds ?? 0)
    if (!seconds) {
      return
    }
    const timer = setInterval(() => {
      setLeft((current) => {
        if (current <= 1) {
          clearInterval(timer)
          return 0
        }
        return current - 1
      })
    }, 1000)
    return () => clearInterval(timer)
  }, [seconds, resetKey])

  return left
}
