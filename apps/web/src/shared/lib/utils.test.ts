import { describe, expect, it } from 'vitest'

// Imported via the `@/*` alias (not a relative path) to prove the alias resolves under Vitest's own
// resolver, independently of `@/shared/lib/utils` already being exercised in real code (src/shared/ui/button.tsx).
import { cn } from '@/shared/lib/utils'

describe('cn (via @/shared/lib/utils alias)', () => {
  it('joins truthy class values and drops falsy ones', () => {
    expect(cn('a', false && 'b', 'c')).toBe('a c')
  })

  it('merges conflicting Tailwind utilities, keeping the last one', () => {
    expect(cn('px-2', 'px-4')).toBe('px-4')
  })
})
