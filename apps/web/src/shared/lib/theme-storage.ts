// What the theme preference is called and which values it can take. DOM-free on purpose: public/theme-init.js is a
// plain script that cannot import this, so vite-plugins/__tests__/theme-init.test.ts imports it from Node and runs
// that script against these names, which is what keeps the two copies from drifting apart.
export const THEME_STORAGE_KEY = 'unihub.theme'

export const THEME_PREFERENCES = ['light', 'dark', 'system'] as const
export type ThemePreference = (typeof THEME_PREFERENCES)[number]

/** A stored value that is neither `light` nor `dark` (absent, `system`, garbage) means "follow the system". */
export function parseThemePreference(value: string | null): ThemePreference {
  return value === 'light' || value === 'dark' ? value : 'system'
}
