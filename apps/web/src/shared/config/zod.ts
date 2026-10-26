import { z } from 'zod'

// Zod 4 compiles object schemas with `new Function` when it can, and finds out by trying one. Under
// our Content-Security-Policy (`script-src 'self'`, no 'unsafe-eval', web-coding-standards.md §11)
// the attempt is caught, but the browser still reports it as a violation on every page load. Our
// schemas are small (an env check, two forms), so the compiled fast path is worth nothing here:
// turning it off removes the probe, and the policy needs no exception for `script-src`.
//
// A side-effect module, imported first by app/main.tsx: the choice is read when a schema is built,
// so it has to be made before the first one is (shared/config/env.ts builds one at import time).
z.config({ jitless: true })
