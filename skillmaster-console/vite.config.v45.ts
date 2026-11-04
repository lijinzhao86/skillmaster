import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

const API = 'http://localhost:8081'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5174,
    proxy: {
      '/web': { target: API },
      '/api/v1': { target: API },
      '/oauth': { target: API },
      '/.well-known': { target: API },
      '/gateway': { target: API },
      '/cli-sign-ins': { target: API },
    },
  },
})
