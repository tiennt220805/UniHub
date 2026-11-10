import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { THEME_STORAGE_KEY } from '@/shared/lib/theme-storage'
import { applyTheme, startThemeSync, useThemeStore } from '../theme-store'

const isDark = () => document.documentElement.classList.contains('dark')

/** A controllable `window.matchMedia` for the dark-scheme query. */
function stubSystem(initiallyDark: boolean) {
  let matches = initiallyDark
  const listeners = new Set<() => void>()
  vi.stubGlobal('matchMedia', (query: string) => ({
    get matches() {
      return query === '(prefers-color-scheme: dark)' && matches
    },
    addEventListener: (_type: string, listener: () => void) => listeners.add(listener),
    removeEventListener: (_type: string, listener: () => void) => listeners.delete(listener),
  }))
  return {
    flip(dark: boolean) {
      matches = dark
      listeners.forEach((listener) => listener())
    },
    listenerCount: () => listeners.size,
  }
}

beforeEach(() => {
  window.localStorage.clear()
  document.documentElement.classList.remove('dark')
  useThemeStore.setState({ preference: 'system' })
})
afterEach(() => vi.unstubAllGlobals())

describe('the browser bar colour (task 7.3)', () => {
  function withBarMetas() {
    document.head.insertAdjacentHTML(
      'beforeend',
      '<meta name="theme-color" media="(prefers-color-scheme: light)" content="#FFFFFF">' +
        '<meta name="theme-color" media="(prefers-color-scheme: dark)" content="#111B15">',
    )
  }
  const bars = () => Array.from(document.querySelectorAll('meta[name="theme-color"]')).map((meta) => meta.getAttribute('content'))
  afterEach(() => document.querySelectorAll('meta[name="theme-color"]').forEach((meta) => meta.remove()))

  it('follows a choice that differs from the system, in both tags, so whichever the browser reads is right', () => {
    stubSystem(false)
    withBarMetas()
    applyTheme('dark')
    expect(bars()).toEqual(['#111B15', '#111B15'])
    applyTheme('light')
    expect(bars()).toEqual(['#FFFFFF', '#FFFFFF'])
  })

  it('on `system` it is the colour of the system scheme', () => {
    stubSystem(true)
    withBarMetas()
    applyTheme('system')
    expect(bars()).toEqual(['#111B15', '#111B15'])
  })

  it('does nothing when the page has no such tag', () => {
    stubSystem(false)
    expect(() => applyTheme('dark')).not.toThrow()
  })
})

describe('the theme store', () => {
  it('puts the dark class on <html> for `dark` and takes it off for `light`', () => {
    stubSystem(false)
    useThemeStore.getState().setPreference('dark')
    expect(isDark()).toBe(true)
    useThemeStore.getState().setPreference('light')
    expect(isDark()).toBe(false)
  })

  it('remembers the choice under the key the init script reads', () => {
    stubSystem(false)
    useThemeStore.getState().setPreference('dark')
    expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark')
    expect(useThemeStore.getState().preference).toBe('dark')
  })

  it('`system` is dark exactly when the operating system is', () => {
    stubSystem(true)
    applyTheme('system')
    expect(isDark()).toBe(true)
    stubSystem(false)
    applyTheme('system')
    expect(isDark()).toBe(false)
  })

  it('still applies the choice when storage is blocked', () => {
    stubSystem(false)
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('QuotaExceededError')
    })
    useThemeStore.getState().setPreference('dark')
    expect(isDark()).toBe(true)
    vi.restoreAllMocks()
  })
})

describe('startThemeSync', () => {
  it('applies the current choice when it starts', () => {
    stubSystem(true)
    const stop = startThemeSync()
    expect(isDark()).toBe(true)
    stop()
  })

  it('follows the operating system while the choice is `system`, and not once it is not', () => {
    const system = stubSystem(false)
    const stop = startThemeSync()

    system.flip(true)
    expect(isDark()).toBe(true)
    system.flip(false)
    expect(isDark()).toBe(false)

    useThemeStore.getState().setPreference('light')
    system.flip(true)
    expect(isDark()).toBe(false)
    stop()
  })

  it('follows another tab that changed the stored choice', () => {
    stubSystem(false)
    const stop = startThemeSync()

    window.dispatchEvent(new StorageEvent('storage', { key: THEME_STORAGE_KEY, newValue: 'dark' }))
    expect(isDark()).toBe(true)
    expect(useThemeStore.getState().preference).toBe('dark')

    window.dispatchEvent(new StorageEvent('storage', { key: 'something.else', newValue: 'light' }))
    expect(isDark()).toBe(true)
    stop()
  })

  it('stops listening when told to', () => {
    const system = stubSystem(false)
    const stop = startThemeSync()
    expect(system.listenerCount()).toBe(1)
    stop()
    expect(system.listenerCount()).toBe(0)
  })
})
