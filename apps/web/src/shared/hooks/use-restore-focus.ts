import { useCallback, useLayoutEffect, useRef, type RefObject } from 'react'

/**
 * Gives focus back when a dialog that has no Radix `Trigger` closes (task 7.4, WCAG 2.4.3). A dialog opened by setting `open`
 * from a click handler, or from a menu item, has no trigger for Radix to return to, so on close focus falls to <body> and a
 * keyboard user starts again from the top of the page.
 *
 * Call it with the dialog's `open` and pass the result to `<AlertDialogContent onCloseAutoFocus>`. It remembers what had focus
 * when `open` turned true (a layout effect, so before Radix moves focus into the dialog) and returns there. When that element
 * is gone, as a menu item is once the menu has closed, `fallback` is used: the menu's own trigger.
 */
export function useRestoreFocus(open: boolean, fallback?: RefObject<HTMLElement | null>) {
  const opener = useRef<HTMLElement | null>(null)

  useLayoutEffect(() => {
    if (!open) return
    const active = document.activeElement
    opener.current = active instanceof HTMLElement && active !== document.body ? active : null
  }, [open])

  return useCallback(
    (event: Event) => {
      const target = opener.current?.isConnected ? opener.current : fallback?.current
      if (!target) return
      event.preventDefault() // Radix would otherwise focus its (absent) trigger
      target.focus()
    },
    [fallback],
  )
}
