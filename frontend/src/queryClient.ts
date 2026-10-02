import { QueryClient } from '@tanstack/react-query'

import { toApiError } from './api/errors'

/**
 * One QueryClient for the app. Server state lives here and nowhere else: there is no
 * Redux and no Zustand (CLAUDE.md forbids both), and with this there is almost no client
 * state left to manage.
 *
 * ---------------------------------------------------------------------------------------
 * THE RETRY POLICY IS WRITTEN AROUND THE RATE LIMITER
 * ---------------------------------------------------------------------------------------
 * The gateway allows each client IP a burst of 40 requests, refilling at 2 a second.
 * TanStack Query retries a failed query three times by default, with backoff. Left alone,
 * that turns one refused request into four, and a page of failing queries into a burst
 * that spends the budget every other request needs.
 *
 *  - A 4xx is NEVER retried. The server understood the request and refused it; sending it
 *    again changes nothing. That includes 429: retrying into a rate limiter is how a
 *    client keeps itself limited. The Retry-After is surfaced to the user instead, who
 *    retries by hand once it has passed (see ErrorNotice).
 *  - A 5xx or no response at all is retried twice. Those can be transient.
 *  - Mutations are not retried at all, which is the library's default: a POST that may or
 *    may not have landed is not something to repeat blindly.
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: (failureCount, error) => !toApiError(error).isClientError && failureCount < 2,
      // Thirty seconds before a refocus or a remount refetches. The catalogue does not
      // change faster than that, and every refetch is a token.
      staleTime: 30_000,
    },
  },
})
