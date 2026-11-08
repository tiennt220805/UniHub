// @vitest-environment node
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

// Lives beside nginx-csp.test.ts because it reads a repo file: this folder is typed with Node (tsconfig.node.json),
// src/ is not, and Vitest blanks a stylesheet imported from src/ even with `?raw`.
const css = readFileSync(path.resolve(import.meta.dirname, '..', '..', 'src', 'styles', 'index.css'), 'utf-8')

// D115 (Phase 8 checklist): text 4.5:1, control borders and the focus ring 3:1, in both modes. This reads the tokens
// from index.css itself, so a typo in a hex value, or a token edited without looking at the table, fails here.
// Evidence for M-86 (contrast half).

function block(selector: string): Record<string, string> {
  const start = css.indexOf(`\n${selector} {`)
  expect(start, `${selector} block`).toBeGreaterThan(-1)
  const body = css.slice(start, css.indexOf('\n}', start))
  const tokens: Record<string, string> = {}
  for (const match of body.matchAll(/--([a-z0-9-]+):\s*(#[0-9A-Fa-f]{6});/g)) tokens[match[1]] = match[2]
  return tokens
}

const modes = { light: block(':root'), dark: block('.dark') }

function rgb(hex: string): [number, number, number] {
  return [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16)) as [number, number, number]
}

function luminance(hex: string): number {
  const [r, g, b] = rgb(hex).map((v) => {
    const s = v / 255
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
  })
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

function ratio(a: string, b: string): number {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x)
  return (hi + 0.05) / (lo + 0.05)
}

/** `top` laid over `under` at `alpha`: how Tailwind's `bg-destructive/10` composites. */
function over(top: string, under: string, alpha: number): string {
  const t = rgb(top)
  const u = rgb(under)
  return `#${t.map((v, i) => Math.round(v * alpha + u[i] * (1 - alpha)).toString(16).padStart(2, '0')).join('')}`
}

// [foreground token, background token] — text pairs, 4.5:1.
const textPairs: [string, string][] = [
  ['foreground', 'background'],
  ['card-foreground', 'card'],
  ['popover-foreground', 'popover'],
  ['muted-foreground', 'background'],
  ['muted-foreground', 'card'],
  ['muted-foreground', 'muted'],
  ['foreground', 'muted'],
  ['foreground', 'accent'],
  ['foreground', 'secondary'],
  ['primary-foreground', 'primary'],
  ['primary-foreground', 'primary-hover'],
  ['primary-text', 'background'],
  ['primary-text', 'card'],
  ['primary-text', 'primary-soft'],
  ['highlight-foreground', 'highlight'],
  ['success', 'success-soft'],
  ['warning', 'warning-soft'],
  ['destructive', 'destructive-soft'],
  ['info', 'info-soft'],
  ['sidebar-foreground', 'sidebar'],
  ['sidebar-foreground', 'sidebar-accent'],
  ['sidebar-primary-foreground', 'sidebar-primary'],
]

// Non-text: a control's border and the focus ring against the surfaces they sit on, 3:1.
const nonTextPairs: [string, string][] = [
  ['input', 'background'],
  ['input', 'card'],
  ['ring', 'background'],
  ['ring', 'card'],
  ['sidebar-ring', 'sidebar'],
  ['seat-ok', 'seat-track'],
  ['seat-low', 'seat-track'],
]

describe.each(Object.entries(modes))('palette B, %s', (_mode, tokens) => {
  it.each(textPairs)('%s on %s is at least 4.5:1', (fg, bg) => {
    expect(tokens[fg], `--${fg}`).toBeDefined()
    expect(tokens[bg], `--${bg}`).toBeDefined()
    expect(ratio(tokens[fg], tokens[bg])).toBeGreaterThanOrEqual(4.5)
  })

  it.each(nonTextPairs)('%s on %s is at least 3:1', (fg, bg) => {
    expect(ratio(tokens[fg], tokens[bg])).toBeGreaterThanOrEqual(3)
  })
})

describe('the destructive button variant (button.tsx: bg-destructive/10, hover /15; dark /20, hover /22) stays readable', () => {
  it.each([
    ['light', 0.1],
    ['light, hovered', 0.15],
    ['dark', 0.2],
    ['dark, hovered', 0.22],
  ] as const)('%s: destructive text on its tinted surface', (mode, alpha) => {
    const tokens = modes[mode.startsWith('dark') ? 'dark' : 'light']
    // The tint sits on the page background and on a card; the lower of the two is the one that matters.
    for (const surface of ['background', 'card']) {
      const tinted = over(tokens.destructive, tokens[surface], alpha)
      expect(ratio(tokens.destructive, tinted)).toBeGreaterThanOrEqual(4.5)
    }
  })
})

describe('the two palettes define the same tokens', () => {
  it('every token exists in both modes', () => {
    expect(Object.keys(modes.dark).sort()).toEqual(Object.keys(modes.light).sort())
  })
})
