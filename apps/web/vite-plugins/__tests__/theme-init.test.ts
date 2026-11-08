// @vitest-environment node
import { readFileSync } from 'node:fs'
import path from 'node:path'
import vm from 'node:vm'
import { describe, expect, it } from 'vitest'

import { THEME_STORAGE_KEY } from '../../src/shared/lib/theme-storage.ts'

// public/theme-init.js is a plain script (no imports), so this runs the real file against stubs of the three browser
// APIs it touches. The storage key comes from the same constant the theme store uses: if either copy is renamed,
// "a stored `dark` wins" fails here instead of as a white flash in a browser. Evidence for M-86.
const SCRIPT = readFileSync(path.resolve(import.meta.dirname, '..', '..', 'public', 'theme-init.js'), 'utf-8')

type World = {
  stored?: string | null
  storageThrows?: boolean
  osDark?: boolean
  noMatchMedia?: boolean
}

/** Runs the script once and returns whether it put `dark` on <html>. */
function run({ stored = null, storageThrows = false, osDark = false, noMatchMedia = false }: World): boolean {
  const classes = new Set<string>()
  const reads: string[] = []
  const window = {
    localStorage: {
      getItem(key: string) {
        reads.push(key)
        if (storageThrows) throw new Error('SecurityError')
        return key === THEME_STORAGE_KEY ? stored : null
      },
    },
    ...(noMatchMedia
      ? {}
      : { matchMedia: (query: string) => ({ matches: query === '(prefers-color-scheme: dark)' && osDark }) }),
  }
  const document = { documentElement: { classList: { add: (name: string) => classes.add(name) } } }
  vm.runInNewContext(SCRIPT, { window, document })
  expect(reads.every((key) => key === THEME_STORAGE_KEY)).toBe(true)
  return classes.has('dark')
}

describe('public/theme-init.js', () => {
  it('reads the key the theme store writes', () => {
    expect(SCRIPT).toContain(`'${THEME_STORAGE_KEY}'`)
  })

  it('a stored choice wins over the operating system', () => {
    expect(run({ stored: 'dark', osDark: false })).toBe(true)
    expect(run({ stored: 'light', osDark: true })).toBe(false)
  })

  it('follows the system when nothing, `system` or garbage is stored', () => {
    for (const stored of [null, 'system', 'purple', '']) {
      expect(run({ stored, osDark: true })).toBe(true)
      expect(run({ stored, osDark: false })).toBe(false)
    }
  })

  it('survives blocked storage and a missing matchMedia, staying light when it cannot tell', () => {
    expect(run({ storageThrows: true, osDark: true })).toBe(true)
    expect(run({ storageThrows: true, osDark: false })).toBe(false)
    expect(run({ stored: 'dark', noMatchMedia: true })).toBe(true)
    expect(run({ noMatchMedia: true })).toBe(false)
  })
})

// A failure here is silent in a browser: if /theme-init.js is not served as a file, nginx's `try_files $uri /index.html` answers 200 with the
// HTML shell, the blocking script is "empty", and the white flash is back without any error. So the three links of the chain are pinned.
describe('how /theme-init.js reaches the browser', () => {
  const web = path.resolve(import.meta.dirname, '..', '..')
  const html = readFileSync(path.join(web, 'index.html'), 'utf-8')

  it('is a file in public/, which Vite copies to the site root', () => {
    expect(SCRIPT.length).toBeGreaterThan(0)
  })

  it('is a blocking classic script in <head>, before the module script', () => {
    const head = html.slice(html.indexOf('<head>'), html.indexOf('</head>'))
    const tag = head.match(/<script\b[^>]*\bsrc="\/theme-init\.js"[^>]*>/)?.[0]
    expect(tag, 'a <script src="/theme-init.js"> in <head>').toBeDefined()
    expect(tag).not.toMatch(/\b(type="module"|defer|async)\b/)
    expect(html.indexOf('/theme-init.js')).toBeLessThan(html.indexOf('/src/app/main.tsx'))
  })

  it('is answered by nginx as a file before the single-page fallback', () => {
    const conf = readFileSync(path.join(web, 'nginx', 'default.conf'), 'utf-8')
    expect(conf).toMatch(/location \/ \{[^}]*try_files \$uri \/index\.html;/)
  })
})
