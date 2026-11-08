import { forwardRef, useEffect, type ElementType, type ReactNode } from 'react'

// A replacement for `react-remove-scroll`, which Radix's modal primitives (Dialog, AlertDialog, Sheet, Select, Menu and
// DropdownMenu) use to lock the page's scroll while they are open. The real package injects a <style> element at run
// time (through react-style-singleton), and the production Content-Security-Policy (`style-src 'self'`, no
// 'unsafe-inline') refuses it: the browser reports a violation every time a modal opens. Phase 8 task 2.3 measured it
// on Chromium, Firefox and WebKit. This one locks the scroll through the CSSOM (`body.style`), which the policy allows.
//
// vite.config.ts aliases the package name to this file, so Radix gets it without being patched.
//
// What is given up: the real package also swallows wheel and touch events aimed at scrollable elements outside the
// open layer. Here the body stops scrolling and the layer's overlay takes the pointer, which is all a modal needs.
// `allowPinchZoom`, `shards` and the other props are accepted and ignored for the same reason.

let locks = 0
let before: { overflow: string; paddingRight: string } | null = null

function lock() {
  if (locks++ > 0) return
  const { body, documentElement } = document
  before = { overflow: body.style.overflow, paddingRight: body.style.paddingRight }
  // Hiding the scrollbar widens the page; pad the body by the scrollbar's width so nothing behind the modal shifts.
  const scrollbar = window.innerWidth - documentElement.clientWidth
  if (scrollbar > 0) {
    body.style.paddingRight = `${scrollbar + (parseFloat(getComputedStyle(body).paddingRight) || 0)}px`
  }
  body.style.overflow = 'hidden'
}

function unlock() {
  if (--locks > 0 || !before) return
  document.body.style.overflow = before.overflow
  document.body.style.paddingRight = before.paddingRight
  before = null
}

type Props = {
  as?: ElementType
  enabled?: boolean
  children?: ReactNode
  // Accepted so that callers written for the real package type-check and run; not used.
  allowPinchZoom?: boolean
  shards?: unknown
  inert?: boolean
  noIsolation?: boolean
  noRelative?: boolean
  removeScrollBar?: boolean
  gapMode?: string
  forwardProps?: boolean
  [prop: string]: unknown
}

export const RemoveScroll = forwardRef<unknown, Props>(function RemoveScroll(
  { as, enabled = true, children, ...rest },
  ref,
) {
  // The index signature of Props widens `as` to unknown, so say what it is.
  const Component: ElementType = (as as ElementType | undefined) ?? 'div'
  useEffect(() => {
    if (!enabled) return
    lock()
    return unlock
  }, [enabled])

  const {
    allowPinchZoom: _allowPinchZoom,
    shards: _shards,
    inert: _inert,
    noIsolation: _noIsolation,
    noRelative: _noRelative,
    removeScrollBar: _removeScrollBar,
    gapMode: _gapMode,
    forwardProps: _forwardProps,
    ...element
  } = rest
  return (
    <Component ref={ref} {...element}>
      {children}
    </Component>
  )
})
