// @vitest-environment node
import { readFile } from 'node:fs/promises'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

import { buildCsp } from '../csp.ts'

const CONF = path.resolve(import.meta.dirname, '..', '..', 'nginx', 'default.conf')

// The one policy the Web image sends as an HTTP header (Phase 7 checklist D85, AC85.1): the build's own
// <meta> policy with VITE_API_BASE_URL empty (the Web and the API share an origin), plus the directive a
// <meta> tag cannot carry.
const HEADER_POLICY = `${buildCsp('')}; frame-ancestors 'none'`

describe('apps/web/nginx/default.conf', () => {
  it('sends the build policy plus frame-ancestors as a Content-Security-Policy header', async () => {
    const conf = await readFile(CONF, 'utf8')
    const headers = [...conf.matchAll(/^\s*add_header\s+Content-Security-Policy\s+"([^"]*)"\s*(\S*);/gm)]

    expect(headers).toHaveLength(1)
    expect(headers[0][1]).toBe(HEADER_POLICY)
    // `always`: the header is also on 404 and 5xx responses.
    expect(headers[0][2]).toBe('always')
  })

  it('sets add_header at server level only, so no location drops the policy', async () => {
    // Comments are dropped first: the file's own explanation mentions `location` and `add_header`.
    const conf = (await readFile(CONF, 'utf8')).replace(/#.*$/gm, '')
    const locations = conf.match(/location\s[^{]*\{[^}]*\}/g) ?? []

    expect(locations.length).toBeGreaterThan(0)
    for (const location of locations) expect(location).not.toMatch(/add_header/)
  })

  it('never lets a script run from anywhere but this origin', () => {
    expect(HEADER_POLICY).toContain("script-src 'self';")
    expect(HEADER_POLICY).not.toContain('unsafe-inline')
    expect(HEADER_POLICY).not.toContain('unsafe-eval')
    expect(HEADER_POLICY.split('; ')).toContain("frame-ancestors 'none'")
  })
})
