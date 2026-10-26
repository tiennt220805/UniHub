import { z } from 'zod'
import { describe, expect, it } from 'vitest'

import '../zod'

describe('shared/config/zod', () => {
  it('turns off the compiled path, so Zod never probes for eval under the CSP (checklist D18)', () => {
    expect(z.config().jitless).toBe(true)
  })

  it('still validates', () => {
    const schema = z.object({ email: z.email(), n: z.number().int() })

    expect(schema.safeParse({ email: 'a@b.test', n: 1 }).success).toBe(true)
    expect(schema.safeParse({ email: 'nope', n: 1.5 }).success).toBe(false)
  })
})
