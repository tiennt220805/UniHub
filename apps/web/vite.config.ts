import path from 'node:path'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
// `defineConfig` from `vitest/config` (not plain `vite`) re-exports Vite's own defineConfig with the
// `test` field's types merged in, so build config and test config stay in one file.
import { defineConfig } from 'vitest/config'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@': path.resolve(import.meta.dirname, './src'),
    },
  },
  server: {
    // Paths in packages/api-contract already carry the /api/v1 prefix, so forward /api as-is to the
    // Backend API (server.port is not overridden in application.yml → Spring Boot default 8080).
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
  },
})
