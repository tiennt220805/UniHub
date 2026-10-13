import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterAll, afterEach, beforeAll } from 'vitest'
import { server } from './msw-server'

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
// Vitest runs with globals off, so RTL's own auto-cleanup never registers; without this a test sees
// the previous test's DOM as soon as a file renders more than once.
afterEach(() => {
  cleanup()
  server.resetHandlers()
})
afterAll(() => server.close())
