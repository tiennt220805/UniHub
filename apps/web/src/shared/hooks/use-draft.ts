import { useEffect, useRef, useState } from 'react'

/** How long typing must pause before a text filter is applied to the URL (D145, D159). */
export const SEARCH_DEBOUNCE_MS = 500

/**
 * A text field that applies to the URL half a second after the last key, and follows the URL when it changes under
 * it (Back, a link). The draft is reset while rendering, the documented way to derive state from a changed prop.
 */
export function useDraft(value: string, apply: (next: string) => void) {
  const [draft, setDraft] = useState(value)
  const [seen, setSeen] = useState(value)
  if (seen !== value) {
    setSeen(value)
    // The URL now holds what this field just applied (trimmed): keep the draft, or a space typed between two words would vanish.
    if (value !== draft.trim()) setDraft(value)
  }
  const applyRef = useRef(apply)
  useEffect(() => {
    applyRef.current = apply
  })
  useEffect(() => {
    const next = draft.trim()
    if (next === value) return
    const timer = setTimeout(() => applyRef.current(next), SEARCH_DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [draft, value])
  return [draft, setDraft] as const
}

