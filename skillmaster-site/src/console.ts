/**
 * Where the console is, and the four addresses this page points at.
 *
 * **One value, build-time.** In development the console dev server is on 5173; in production it is
 * `https://console.skillmasterai.com`. A trailing slash is stripped because every address here is
 * built by appending a path — a doubled slash is a different URL to every proxy in the chain.
 *
 * **A production build without `VITE_CONSOLE_URL` does not exist**: `vite.config.ts` refuses to
 * build one. The fallback below is therefore only ever a developer's machine — it is here so
 * `npm run dev` works with no setup, not so a deployment can ship pointing at localhost.
 */

const RAW = import.meta.env.VITE_CONSOLE_URL
const FALLBACK = 'http://localhost:5173'

/** The console's origin, without a trailing slash. */
export const CONSOLE_URL = (RAW?.trim() || FALLBACK).replace(/\/+$/, '')

/** `''` is the console's own home, which is the one address that ends in a slash. */
export function consoleLink(path: '' | '/login' | '/register' | '/logout'): string {
  return path === '' ? CONSOLE_URL + '/' : CONSOLE_URL + path
}
