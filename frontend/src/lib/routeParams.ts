/**
 * A route parameter that should be a database id: a positive whole number, or null.
 *
 * Checked here, before a request is made, for two reasons. "abc" or "1.5" would otherwise
 * go to the API and come back as a 400 worded for a developer ("Parameter 'id' is not a
 * valid value"), where the honest thing to tell a person is that the page does not exist.
 * And a path built from the result is built from a NUMBER, never from text taken out of
 * the URL - which is what keeps a navigation target from being something a link author
 * chose.
 */
export function parseId(param: string | undefined): number | null {
  if (param === undefined || !/^[1-9][0-9]{0,15}$/.test(param)) {
    return null
  }
  return Number(param)
}
