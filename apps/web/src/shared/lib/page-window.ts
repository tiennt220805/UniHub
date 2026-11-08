/** The page numbers to show: the first, the last, and a window around the current one, with `null` for a gap. */
export function pageWindow(page: number, pageCount: number): (number | null)[] {
  const keep = new Set([0, pageCount - 1, page - 1, page, page + 1])
  const shown = [...keep].filter((n) => n >= 0 && n < pageCount).sort((a, b) => a - b)
  const out: (number | null)[] = []
  shown.forEach((n, i) => {
    if (i > 0 && n - shown[i - 1] > 1) out.push(null)
    out.push(n)
  })
  return out
}
