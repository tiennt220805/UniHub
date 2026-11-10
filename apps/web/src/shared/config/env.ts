import { z } from './zod'

// Parsed once at import: a bad value fails at startup, not mid-request.
const schema = z.object({
  // Empty = same origin. In dev the Vite proxy forwards /api to the Backend API (vite.config.ts);
  // contract paths already carry the /api/v1 prefix.
  VITE_API_BASE_URL: z.string().default(''),
  // Phase 2 collects no card data: checkout offers a fixed list of Stripe test tokens instead of
  // Stripe.js (checklist D11). The list is shown only when this is on, so a build that is not meant
  // for the sandbox has no way to pick a token. Off unless a build asks for it.
  VITE_PAYMENT_TEST_MODE: z.stringbool().default(false),
})

const parsed = schema.parse(import.meta.env)

// Not frozen: a test switches paymentTestMode to cover both modes of the same page.
export const env = {
  apiBaseUrl: parsed.VITE_API_BASE_URL,
  paymentTestMode: parsed.VITE_PAYMENT_TEST_MODE,
}
