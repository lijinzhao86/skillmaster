/**
 * Where the marketing site is: the console's one door out (ADR 0086).
 *
 * **Two places use it, and they must agree** — the top bar's brand, which is a home link, and
 * `/logout`, which sends the person back to the site after signing out. Both read this value, so a
 * wrong address is wrong in one place instead of two.
 *
 * A build without `VITE_SITE_URL` is refused (see `vite.config.ts`): the fallback below is the dev
 * server's site port, which is right for `npm run dev` and for the tests and wrong for everything
 * else — a console that sent people to a machine that is not there would look like a bug in the
 * link, not in the build.
 */
const ORIGIN = ((import.meta.env.VITE_SITE_URL ?? '').trim() || 'http://localhost:5174').replace(/\/+$/, '')

/** With the trailing slash: it names a directory, and every caller uses it as one. */
export const SITE_URL = ORIGIN + '/'
