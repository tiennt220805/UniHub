// Sets the colour theme before the first paint, so a signed-out visitor in dark mode never sees a white flash
// (Phase 8 checklist Q8, D124). index.html loads it as a blocking classic <script src> at the top of <head>:
// the Content-Security-Policy has no 'unsafe-inline', so the code cannot sit inline, and a module script would
// run after the first paint.
//
// What it reads and the class it sets are mirrored in src/shared/lib/theme-storage.ts and
// src/shared/store/theme-store.ts; vite-plugins/__tests__/theme-init.test.ts runs this file to keep them equal.
// Following the system while the page is open is the store's job, not this script's.
;(function () {
  var dark = false
  var stored = null
  try {
    stored = window.localStorage.getItem('unihub.theme')
  } catch {
    // Storage blocked (private window, site data off): the preference is simply unknown.
  }
  if (stored === 'dark') {
    dark = true
  } else if (stored !== 'light') {
    try {
      dark = window.matchMedia('(prefers-color-scheme: dark)').matches
    } catch {
      // No matchMedia: stay light.
    }
  }
  if (dark) document.documentElement.classList.add('dark')
})()
