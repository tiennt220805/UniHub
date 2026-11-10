import '@testing-library/jest-dom/vitest'
import { cleanup, configure } from '@testing-library/react'
import { afterAll, afterEach, beforeAll } from 'vitest'

import { endSession } from '@/features/auth'
import { server } from './msw-server'

// RTL waits 1 s for a findBy/waitFor by default. The first test of a file renders a whole page cold, and with the suite running
// in parallel on a busy machine that has taken 1.3 s (WorkshopListPage, Phase 8 task 7.5): a timing flake, not a bug. A failing
// test still fails; it just takes longer to say so.
configure({ asyncUtilTimeout: 4000 })

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
// Vitest runs with globals off, so RTL's own auto-cleanup never registers; without this a test sees
// the previous test's DOM as soon as a file renders more than once.
afterEach(() => {
  cleanup()
  server.resetHandlers()
  // The session lives in a module-level store and in sessionStorage: it must not leak between tests.
  endSession()
})
afterAll(() => server.close())
