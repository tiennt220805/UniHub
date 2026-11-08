import path from 'node:path'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
// `defineConfig` from `vitest/config` (not plain `vite`) re-exports Vite's own defineConfig with the
// `test` field's types merged in, so build config and test config stay in one file.
import { loadEnv } from 'vite'
import { defineConfig } from 'vitest/config'
import { cspMeta } from './vite-plugins/csp.ts'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, import.meta.dirname, 'VITE_')
  return {
    // The CSP meta tag is added to production builds only (vite-plugins/csp.ts).
    plugins: [react(), tailwindcss(), cspMeta(env.VITE_API_BASE_URL ?? '')],
    resolve: {
      alias: [
        { find: '@', replacement: path.resolve(import.meta.dirname, './src') },
        // Radix's modals lock the page scroll with react-remove-scroll, which injects a <style> element that the
        // production CSP refuses. This file does the same through the CSSOM (Phase 8 task 2.3).
        {
          find: /^react-remove-scroll$/,
          replacement: path.resolve(import.meta.dirname, './src/shared/lib/csp-safe-remove-scroll.tsx'),
        },
      ],
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
      setupFiles: ['./src/testing/setup.ts'],
    },
  }
})
