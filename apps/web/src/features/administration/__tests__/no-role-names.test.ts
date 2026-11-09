import { describe, expect, it } from 'vitest'

// Rules #13 (web-coding-standards.md §11): UI is gated by permission, never by role name. A role's name may be shown as text (the
// admin frame prints it under the user's name); it may not be compared, tested or branched on. This reads the source of the
// administration code and the admin routes and fails on the shapes a role check takes.

const sources = import.meta.glob(['../**/*.{ts,tsx}', '../../../routes/admin/*.{ts,tsx}', '../../../app/router/*.{ts,tsx}', '../../../app/router/guards/*.{ts,tsx}'], {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>

const FORBIDDEN: [RegExp, string][] = [
  [/\.roles\b(?!\s*\.join)/, 'reads session.user.roles (only `.roles.join(...)`, to print it, is allowed)'],
  [/['"`](Organizer|Admin|Student|Check-in Staff)['"`]/, 'names a role'],
  [/\bisAdmin\b|\bisOrganizer\b|\bhasRole\b/, 'asks about a role'],
]

const withoutComments = (text: string) => text.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')

describe('administration code asks for permissions, not roles', () => {
  const files = Object.entries(sources).filter(([path]) => !path.includes('__tests__'))

  it('sees the files it is meant to check', () => {
    expect(files.length).toBeGreaterThan(20)
  })

  for (const [pattern, why] of FORBIDDEN) {
    it(`nothing ${why}`, () => {
      const hits = files.filter(([, text]) => pattern.test(withoutComments(text))).map(([path]) => path)
      expect(hits).toEqual([])
    })
  }

  it('would notice a role check (the guard itself is tested)', () => {
    expect(FORBIDDEN[0][0].test("if (session.user.roles.includes('Admin')) {}")).toBe(true)
    expect(FORBIDDEN[0][0].test("session.user.roles.join(', ')")).toBe(false)
    expect(FORBIDDEN[1][0].test("role === 'Organizer'")).toBe(true)
  })
})
