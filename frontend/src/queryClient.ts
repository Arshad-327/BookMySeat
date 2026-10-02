import { QueryClient } from '@tanstack/react-query'

/**
 * One QueryClient for the app. Server state lives here and nowhere else: there is no
 * Redux and no Zustand (CLAUDE.md forbids both), and with this there is almost no client
 * state left to manage.
 *
 * The retry policy is set in the commit that adds the API client, because it has to read
 * the status of a failed request and that type does not exist yet.
 */
export const queryClient = new QueryClient()
