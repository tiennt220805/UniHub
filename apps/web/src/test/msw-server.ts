import { setupServer } from 'msw/node'

// No default handlers: each test declares the endpoints it needs via server.use(...), and an
// unhandled request errors out (onUnhandledFrame: 'error', see setup.ts) instead of silently
// hitting the network.
export const server = setupServer()
