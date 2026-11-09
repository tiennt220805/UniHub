// The picture a workshop without a cover image gets (D123, D144): the same shapes in the same colours for the same id,
// drawn as SVG with presentation attributes only. No <style> element and no style="" attribute: the CSP's `style-src`
// refuses both. The hex values are the palette-B colours written out, because an SVG fill is not a Tailwind class and
// a generated cover should look the same in light and dark mode.

export type CoverFamily = 'sun' | 'bars' | 'tiles'

export type CoverArt = {
  family: CoverFamily
  background: string
  primary: string
  secondary: string
  /** 0–1 positions and a turn in degrees, from the id, so two workshops with one family still differ. */
  x: number
  y: number
  turn: number
}

const PALETTES = [
  { background: '#DCFCE7', primary: '#15803D', secondary: '#FCD34D' },
  { background: '#FCD34D', primary: '#166534', secondary: '#FFFFFF' },
  { background: '#15803D', primary: '#126F33', secondary: '#DCFCE7' },
  { background: '#14261B', primary: '#15803D', secondary: '#FCD34D' },
  { background: '#DCFCE7', primary: '#FCD34D', secondary: '#15803D' },
] as const

const FAMILIES: CoverFamily[] = ['sun', 'bars', 'tiles']

/** FNV-1a, 32 bit: small, and the same in every browser. */
function hash(text: string): number {
  let h = 0x811c9dc5
  for (let i = 0; i < text.length; i++) {
    h ^= text.charCodeAt(i)
    h = Math.imul(h, 0x01000193)
  }
  return h >>> 0
}

export function coverArt(workshopId: string): CoverArt {
  const h = hash(workshopId)
  const palette = PALETTES[(h >>> 4) % PALETTES.length]
  return {
    family: FAMILIES[h % FAMILIES.length],
    ...palette,
    x: ((h >>> 8) & 0xff) / 255,
    y: ((h >>> 16) & 0xff) / 255,
    turn: (((h >>> 24) & 0xff) / 255) * 40 - 20,
  }
}
