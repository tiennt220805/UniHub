// @vitest-environment node
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { build, createServer } from 'vite'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'

import { buildCsp, cspMeta } from '../csp.ts'

const ROOT = path.resolve(import.meta.dirname, '..', '..')

// The directives of checklist D14, one per entry. A change here is a change of security policy.
const DIRECTIVES = [
  "default-src 'self'",
  "script-src 'self'",
  "style-src 'self'",
  "img-src 'self' data:",
  "font-src 'self'",
  "connect-src 'self'",
  "object-src 'none'",
  "base-uri 'self'",
  "form-action 'self'",
]

describe('buildCsp', () => {
  it('has exactly the directives of D14', () => {
    expect(buildCsp('').split('; ')).toEqual(DIRECTIVES)
  })

  it('allows the Backend API origin, and only its origin, when the API is on another host', () => {
    expect(buildCsp('https://api.unihub.test/some/base/path')).toContain(
      "connect-src 'self' https://api.unihub.test;",
    )
  })

  it('never lets a script run from anywhere but this origin (AC18.2)', () => {
    const csp = buildCsp('https://api.unihub.test')

    expect(csp).toContain("script-src 'self';")
    expect(csp).not.toContain('unsafe-inline')
    expect(csp).not.toContain('unsafe-eval')
  })
})

describe('the production build (AC14.0)', () => {
  let outDir: string
  let html: string

  beforeAll(async () => {
    outDir = await mkdtemp(path.join(tmpdir(), 'unihub-web-build-'))
    await build({
      root: ROOT,
      configFile: path.join(ROOT, 'vite.config.ts'),
      mode: 'production',
      logLevel: 'silent',
      build: { outDir, emptyOutDir: true },
    })
    html = await readFile(path.join(outDir, 'index.html'), 'utf8')
  }, 120_000)

  afterAll(() => rm(outDir, { recursive: true, force: true }))

  it('carries the CSP meta tag with the D14 directives', () => {
    const tag = html.match(/<meta[^>]*http-equiv="Content-Security-Policy"[^>]*>/)?.[0]
    // The serializer writes a quote in an attribute as &#39;; the browser reads it back as '.
    const content = tag?.match(/content="([^"]*)"/)?.[1].replaceAll('&#39;', "'")

    expect(tag).toBeDefined()
    expect(content?.split('; ')).toEqual(DIRECTIVES)
  })

  it('places the policy before any script or stylesheet it governs', () => {
    const policy = html.indexOf('Content-Security-Policy')
    const firstResource = html.search(/<script|<link rel="stylesheet"/)

    expect(policy).toBeGreaterThan(-1)
    expect(policy).toBeLessThan(firstResource)
  })

  it('has no inline script: every <script> loads a file', () => {
    const scripts = html.match(/<script\b[^>]*>/g) ?? []

    expect(scripts.length).toBeGreaterThan(0)
    for (const script of scripts) expect(script).toMatch(/\bsrc="/)
    expect(html).not.toMatch(/<script\b(?![^>]*\bsrc=)[^>]*>\s*\S/)
  })

  it('has no inline <style> element and no inline event handler', () => {
    expect(html).not.toMatch(/<style\b/)
    expect(html).not.toMatch(/\son[a-z]+=/i)
  })
})

describe('the dev server', () => {
  it('serves index.html with no CSP, so hot reload keeps working (AC14.0)', async () => {
    const server = await createServer({
      root: ROOT,
      configFile: path.join(ROOT, 'vite.config.ts'),
      mode: 'development',
      logLevel: 'silent',
      appType: 'custom',
      server: { middlewareMode: true },
    })
    try {
      const html = await server.transformIndexHtml('/', await readFile(path.join(ROOT, 'index.html'), 'utf8'))

      expect(html).not.toContain('Content-Security-Policy')
    } finally {
      await server.close()
    }
  }, 60_000)

  it('the plugin applies to builds only', () => {
    expect(cspMeta('').apply).toBe('build')
  })
})
