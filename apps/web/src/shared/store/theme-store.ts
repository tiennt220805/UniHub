import { create } from 'zustand'

import { parseThemePreference, THEME_STORAGE_KEY, type ThemePreference } from '@/shared/lib/theme-storage'

const DARK_QUERY = '(prefers-color-scheme: dark)'

type ThemeState = {
  /** What the user chose. `system` follows the operating system, including while the page is open. */
  preference: ThemePreference
  setPreference: (preference: ThemePreference) => void
}

function readPreference(): ThemePreference {
  try {
    return parseThemePreference(window.localStorage.getItem(THEME_STORAGE_KEY))
  } catch {
    return 'system'
  }
}

function systemPrefersDark(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia(DARK_QUERY).matches
}

// The `card` token of each theme: the colour at the top of every page is the header, and the browser bar should match it.
const THEME_COLOR = { light: '#FFFFFF', dark: '#111B15' } as const

/** Puts the `.dark` class on <html>, the only thing the design tokens in index.css look at, and the browser bar with it. */
export function applyTheme(preference: ThemePreference): void {
  const dark = preference === 'dark' || (preference === 'system' && systemPrefersDark())
  document.documentElement.classList.toggle('dark', dark)
  // index.html has one <meta name="theme-color"> per system scheme, which cannot follow a choice that differs from the
  // system; setting both to the colour in force can, and with the choice on `system` it is the matching one anyway.
  document
    .querySelectorAll<HTMLMetaElement>('meta[name="theme-color"]')
    .forEach((meta) => meta.setAttribute('content', dark ? THEME_COLOR.dark : THEME_COLOR.light))
}

// Only the choice lives here (web-coding-standards.md §8): whether the page is dark right now is the class on <html>,
// set before first paint by public/theme-init.js and afterwards by applyTheme().
export const useThemeStore = create<ThemeState>()((set) => ({
  preference: readPreference(),
  setPreference: (preference) => {
    try {
      window.localStorage.setItem(THEME_STORAGE_KEY, preference)
    } catch {
      // Storage blocked: the choice still applies for this page view, it just is not remembered.
    }
    applyTheme(preference)
    set({ preference })
  },
}))

/**
 * Keeps the page in step with what changes outside it: the operating system flipping between light and dark while
 * the choice is `system`, and another tab changing the stored choice. Called once from app/main.tsx; returns a
 * function that stops both.
 */
export function startThemeSync(): () => void {
  const media = typeof window.matchMedia === 'function' ? window.matchMedia(DARK_QUERY) : null
  const onSystemChange = () => applyTheme(useThemeStore.getState().preference)
  // The init script has normally done this already; this covers a page that was served without it (a stale cache).
  onSystemChange()
  const onStorage = (event: StorageEvent) => {
    if (event.key !== THEME_STORAGE_KEY) return
    const preference = parseThemePreference(event.newValue)
    useThemeStore.setState({ preference })
    applyTheme(preference)
  }
  media?.addEventListener('change', onSystemChange)
  window.addEventListener('storage', onStorage)
  return () => {
    media?.removeEventListener('change', onSystemChange)
    window.removeEventListener('storage', onStorage)
  }
}
