import { z } from 'zod'

// Zod 4 compiles object schemas with `new Function` when it can, and finds out by trying one. Under
// our Content-Security-Policy (`script-src 'self'`, no 'unsafe-eval', web-coding-standards.md §11)
// the attempt is caught, but the browser still reports it as a violation on every page load. Our
// schemas are small (an env check, two forms), so the compiled fast path is worth nothing here:
// turning it off removes the probe, and the policy needs no exception for `script-src`.
//
// The choice is read when a schema is built, so it has to be made before the first one is. Every file that builds a
// schema therefore imports `z` from HERE, not from 'zod' (ESLint enforces it): the import graph, not the order of
// imports in app/main.tsx, then guarantees the order. That mattered once the administration pages became lazy
// chunks (Phase 8 task 7.5): the bundler moved a schema module into a shared chunk that is evaluated before the
// entry chunk, so "imported first by main.tsx" no longer meant first, and the probe came back on /login.
z.config({ jitless: true })

export { z }
