/**
 * "After signing in, go back to the show I was looking at."
 *
 * The show id travels in the router's HISTORY STATE, never in the URL, and the path is
 * rebuilt here from a number. That is deliberate. The usual shape - /login?next=/shows/1 -
 * puts a navigation target in a place anybody can write a link to, and react-router 6 has
 * an open-redirect advisory for exactly that. History state cannot be set by a link from
 * another site, and a positive integer cannot be turned into anything but /shows/N.
 */
export interface ReturnToShowState {
  returnToShowId: number
}

/** The state to hand a Link or navigate() that leads to the login or register page. */
export function returnToShow(showId: number): ReturnToShowState {
  return { returnToShowId: showId }
}

/** Where to go once signed in: the show named in the history state, or the browse page. */
export function destinationAfterSignIn(state: unknown): string {
  const id =
    typeof state === 'object' && state !== null ? (state as Record<string, unknown>).returnToShowId : undefined
  return typeof id === 'number' && Number.isSafeInteger(id) && id > 0 ? `/shows/${id}` : '/'
}
