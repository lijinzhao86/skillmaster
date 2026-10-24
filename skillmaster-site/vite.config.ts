import react from '@vitejs/plugin-react'
import { loadEnv } from 'vite'
import { defineConfig } from 'vitest/config'

/** Where the Spring Boot server runs locally. */
const API = 'http://localhost:8080'

/**
 * Refuses to build without the console's origin.
 *
 * **The alternative is a page that looks deployed and is not.** A missing value falls back to
 * `localhost:5173` for the session read and for every link, so the site would ship green, load
 * fine, and send every visitor's 登录 to a machine that is not there — with no error anywhere. The
 * build is the last place this can still be seen, so it is where it stops.
 */
function requireConsoleOrigin(mode: string): void {
  const value = (loadEnv(mode, process.cwd(), 'VITE_').VITE_CONSOLE_URL ?? '').trim()
  if (value === '') {
    throw new Error('VITE_CONSOLE_URL must be set for a build — see README.md.')
  }
  // It has to parse, at least: a typo here ships and breaks every address the page builds from it.
  new URL(value)
}

export default defineConfig(({ command, mode }) => {
  if (command === 'build') {
    requireConsoleOrigin(mode)
  }

  return {
    plugins: [react()],

    server: {
      // 5173 is the console's; the site takes the next one so both can run at once.
      port: 5174,

      // **`/web/` is proxied, and that is the development answer to ADR 0086's CORS rule.**
      // In production the site calls `https://console…/web/session` cross-origin and the server
      // allows exactly that origin; in development the two origins are different ports, and asking
      // the server to allow every laptop's port would weaken the rule that only exists to be narrow.
      // A proxy makes the request same-origin instead, so the browser sends the session cookie with
      // no CORS and no `credentials` negotiation. Ports do not scope cookies, so the session the
      // console set on 5173 (or the server set through it) is the one this page reads on 5174.
      //
      // An explicit prefix, not `/web`: the bare form also matches `/webhooks` and anything else a
      // future path starts with, and each of those would be silently proxied to the server.
      //
      // `changeOrigin` is deliberately absent for the same reason the console omits it: the server
      // builds absolute redirects from the address it was reached at, and rewriting `Host` sends the
      // browser to the server's own origin for pages this app serves.
      proxy: {
        '^/web/': { target: API },
      },
    },

    build: {
      // The repository's root .gitignore ignores `dist/`, so the build output is never committed.
      outDir: 'dist',
    },

    test: {
      environment: 'jsdom',
      setupFiles: ['./tests/setup.ts'],
      include: ['tests/**/*.test.ts', 'tests/**/*.test.tsx'],
    },
  }
})
