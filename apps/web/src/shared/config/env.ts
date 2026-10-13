import { z } from 'zod'

// Parsed once at import: a bad value fails at startup, not mid-request.
const schema = z.object({
  // Empty = same origin. In dev the Vite proxy forwards /api to the Backend API (vite.config.ts);
  // contract paths already carry the /api/v1 prefix.
  VITE_API_BASE_URL: z.string().default(''),
})

const parsed = schema.parse(import.meta.env)

export const env = { apiBaseUrl: parsed.VITE_API_BASE_URL }
