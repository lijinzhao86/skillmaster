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
    proxy: {
      '/web': { target: API, changeOrigin: true },
      '/api/v1': { target: API, changeOrigin: true },
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
