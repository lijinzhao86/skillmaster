/**
 * Where to send somebody who has to sign in before a page will answer.
 *
 * `return_to` is the parameter the sign-in page already reads, and it is what makes a deep link work:
 * the CLI submits a skill and opens `/skills/<namespace>/<name>`, and a person who is not signed in
 * when that tab opens has to come back to that address rather than to the home page.
 *
 * Encoded, because the value is a path and the whole thing is one query parameter — an unencoded
 * second `?` or `#` would end the parameter early and send the person somewhere else. The sign-in
 * page decodes it and then checks its origin, so nothing about trusting this value is implied by
 * writing it.
 */
export function loginPath(): string {
  const here = window.location.pathname + window.location.search
  return `/login?return_to=${encodeURIComponent(here)}`
}
