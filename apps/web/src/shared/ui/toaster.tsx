import { CircleCheck, CircleX, Info, X } from "lucide-react"
import * as React from "react"

import { cn } from "@/shared/lib/utils"
import {
  TOAST_AUTO_DISMISS_MS,
  useToastStore,
  type ToastItem,
  type ToastTone,
} from "@/shared/store/toast-store"
import { Button } from "@/shared/ui/button"

// Our own toast (D133): Sonner injects a <style> element, which `style-src 'self'` refuses, so its toaster is unstyled
// in production. This one is plain classes and a Zustand store.
//
// Both live regions are in the DOM from the first render and start empty, because a screen reader only announces what is
// added to a region that already exists. Success and information are polite; an error is assertive and stays until closed.
// The regions are plain `aria-live` elements with no role: an always-present `role="alert"` would answer every
// `getByRole('alert')` of a page's own error with a second, empty match.

const ICON: Record<ToastTone, React.ReactNode> = {
  success: <CircleCheck className="text-primary-text" />,
  info: <Info className="text-info" />,
  error: <CircleX className="text-destructive" />,
}

function ToastView({ toast }: { toast: ToastItem }) {
  const dismiss = useToastStore((state) => state.dismiss)
  const [paused, setPaused] = React.useState(false)
  const limit = TOAST_AUTO_DISMISS_MS[toast.tone]

  // Pointing at a toast or focusing inside it holds the timer (WCAG 2.2.1: time the user can stop).
  React.useEffect(() => {
    if (limit === null || paused || toast.closing) return
    const timer = setTimeout(() => dismiss(toast.id), limit)
    return () => clearTimeout(timer)
  }, [limit, paused, toast.closing, toast.id, dismiss])

  return (
    <div
      data-slot="toast"
      data-state={toast.closing ? "closed" : "open"}
      data-tone={toast.tone}
      onMouseEnter={() => setPaused(true)}
      onMouseLeave={() => setPaused(false)}
      onFocus={() => setPaused(true)}
      onBlur={() => setPaused(false)}
      className={cn(
        "pointer-events-auto flex w-full items-center gap-3 rounded-xl border border-border bg-popover px-3.5 py-3 text-sm text-popover-foreground shadow-lg dark:shadow-none sm:w-96",
        // Enters from below in 200 ms, leaves faster; reduced motion keeps the fade and drops the movement.
        "transition-[opacity,transform] duration-200 ease-out-strong starting:translate-y-2 starting:opacity-0 data-[state=closed]:translate-y-2 data-[state=closed]:opacity-0 data-[state=closed]:duration-150",
        "motion-reduce:translate-y-0 motion-reduce:data-[state=closed]:translate-y-0 motion-reduce:starting:translate-y-0",
      )}
    >
      <span aria-hidden="true" className="grid shrink-0 [&_svg]:size-5">
        {ICON[toast.tone]}
      </span>
      <span className="min-w-0 flex-1 leading-5">{toast.message}</span>
      {toast.action ? (
        <Button
          variant="outline"
          size="xs"
          onClick={() => {
            toast.action?.onClick()
            dismiss(toast.id)
          }}
        >
          {toast.action.label}
        </Button>
      ) : null}
      <Button variant="ghost" size="icon-xs" aria-label="Đóng thông báo" onClick={() => dismiss(toast.id)}>
        <X />
      </Button>
    </div>
  )
}

const region =
  "pointer-events-none flex w-full flex-col items-center gap-2 sm:items-end"

/** Mount once, near the root. */
export function Toaster() {
  const toasts = useToastStore((state) => state.toasts)
  return (
    <div className="pointer-events-none fixed inset-x-0 bottom-0 z-[60] flex flex-col gap-2 p-4 pb-[max(1rem,env(safe-area-inset-bottom))]">
      <div aria-live="polite" aria-label="Thông báo" className={region}>
        {toasts.filter((t) => t.tone !== "error").map((t) => (
          <ToastView key={t.id} toast={t} />
        ))}
      </div>
      <div aria-live="assertive" aria-label="Lỗi" className={region}>
        {toasts.filter((t) => t.tone === "error").map((t) => (
          <ToastView key={t.id} toast={t} />
        ))}
      </div>
    </div>
  )
}
