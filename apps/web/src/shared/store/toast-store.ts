import { create } from 'zustand'

// UI messages only (web-coding-standards.md §8): what to tell the user, and for how long. No server data lives here.

export type ToastTone = 'success' | 'info' | 'error'

export type ToastAction = { label: string; onClick: () => void }

export type ToastItem = {
  id: number
  tone: ToastTone
  message: string
  action?: ToastAction
  /** Set when it starts leaving: the exit transition runs, then the item is removed. */
  closing: boolean
}

/** How long the exit transition takes; kept in step with the `duration-150` in toaster.tsx. */
export const TOAST_EXIT_MS = 150
/** An error is never dismissed by a timer: a message about something that went wrong has to be read. */
export const TOAST_AUTO_DISMISS_MS: Record<ToastTone, number | null> = { success: 5000, info: 5000, error: null }
const MAX_VISIBLE = 3

type ToastState = {
  toasts: ToastItem[]
  push: (tone: ToastTone, message: string, options?: { action?: ToastAction }) => number
  dismiss: (id: number) => void
}

let nextId = 1

export const useToastStore = create<ToastState>()((set, get) => ({
  toasts: [],
  push: (tone, message, options) => {
    const id = nextId++
    set((state) => {
      const open = state.toasts.filter((t) => !t.closing)
      // The oldest leaves when a fourth arrives, so a burst never stacks up the screen.
      const overflow = open.length >= MAX_VISIBLE ? open.slice(0, open.length - MAX_VISIBLE + 1).map((t) => t.id) : []
      overflow.forEach((old) => setTimeout(() => get().dismiss(old), 0))
      return { toasts: [...state.toasts, { id, tone, message, action: options?.action, closing: false }] }
    })
    return id
  },
  dismiss: (id) => {
    const target = get().toasts.find((t) => t.id === id)
    if (!target || target.closing) return
    set((state) => ({ toasts: state.toasts.map((t) => (t.id === id ? { ...t, closing: true } : t)) }))
    setTimeout(() => set((state) => ({ toasts: state.toasts.filter((t) => t.id !== id) })), TOAST_EXIT_MS)
  },
}))

/** Call from anywhere: `toast.success('Đã lưu thay đổi.')`. The <Toaster /> in app/App.tsx draws it. */
export const toast = {
  success: (message: string, options?: { action?: ToastAction }) => useToastStore.getState().push('success', message, options),
  info: (message: string, options?: { action?: ToastAction }) => useToastStore.getState().push('info', message, options),
  error: (message: string, options?: { action?: ToastAction }) => useToastStore.getState().push('error', message, options),
  dismiss: (id: number) => useToastStore.getState().dismiss(id),
}
