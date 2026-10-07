import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vitest/config'

/** Where the Spring Boot server runs locally. */
const API = 'http://localhost:8080'

export default defineConfig({
  plugins: [vue()],

  server: {
    port: 5173,
    // The browser talks to this origin for everything, exactly as it will in production behind the
    // reverse proxy. That is not a convenience: the session cookie is SameSite=Lax and the CSRF
    // token is echoed by hand, so a frontend on its own origin posting to another one would lose
    // both. Nothing here needs cookie rewriting because neither cookie carries a Domain.
    //
    // **`changeOrigin` is deliberately not set, and turning it on breaks the sign-in.** It rewrites
    // the `Host` header to the target's, which is what a virtual-hosted backend needs and the
    // opposite of what this one needs: the server builds absolute redirects from the address it
    // believes it was reached at, so with `Host: localhost:8080` the authorization endpoint answers
    // `Location: http://localhost:8080/login` — the server's own origin, where nothing serves pages,
    // instead of this app's `/login`. Found by driving the CLI's login (2026-10-05); nothing before
    // it noticed, because every earlier flow here was JSON and the app navigated itself.
    //
    // Leaving the header alone is also what the real proxy does — ADR 0015's nginx passes
    // `proxy_set_header Host $host` — so this is the dev side matching production rather than
    // working around it.
    proxy: {
      '/web': { target: API },
      '/api/v1': { target: API },
      // The token plane, and it is here for the same reason as the two above rather than for
      // convenience: the authorization server redirects the browser to `/consent` and `/login`, which
      // are pages of *this* app, and it builds those redirects from the address the request arrived
      // on. Without these entries the browser would be sent to the server's own origin for a page the
      // server does not serve — a 404 in the middle of a sign-in that had otherwise worked. The
      // discovery document has to come through here too, because it is what tells a client where the
      // endpoints are and those answers have to be this origin's.
      '/oauth': { target: API },
      '/.well-known': { target: API },
      // §4.5's gateway skill, which the CLI fetches **before** it has any credential — and which
      // must be proxied for a reason that has nothing to do with sign-in: Vite answers any unmatched
      // path with this app's `index.html` and a 200, so without this entry a `skillmaster setup`
      // against 5173 installs a web page as a skill and reports success. There is no 404 to notice.
      // Found by doing it (2026-10-05).
      '/gateway': { target: API },
    },
  },

  build: {
    // Vite's default, stated because something depends on it: the repository's root .gitignore
    // ignores `dist/`, so the build output is not committed under that name.
    outDir: 'dist',
  },

  test: {
    environment: 'jsdom',
    include: ['tests/**/*.test.ts'],
  },
})
