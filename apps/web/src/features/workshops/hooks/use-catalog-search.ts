import { useCallback, useEffect, useMemo, useRef } from 'react'
import { useSearchParams } from 'react-router'

import { parseCatalogSearch, toCatalogParams, type CatalogSearch } from '../model/catalog-filters'

/**
 * The catalog's filters, read from and written to the URL (D145). Any change but a page change returns to the first
 * page. `replace` keeps typing out of the history; a date or a page pushes an entry, so Back goes to the previous view.
 */
export function useCatalogSearch() {
  const [params, setParams] = useSearchParams()
  const search = useMemo(() => parseCatalogSearch(params), [params])

  // The newest filters, including a write that has not rendered yet. Two text fields whose debounce timers fire in one turn
  // (clear one box, fill the next) would otherwise both build their patch on the same stale copy, and the second write
  // would put the first one's old value back (found on the cluster run, Phase 8 task 9.3).
  const latest = useRef(search)
  useEffect(() => {
    latest.current = search
  }, [search])

  const update = useCallback(
    (patch: Partial<CatalogSearch>, options: { replace?: boolean } = {}) => {
      const next = { ...latest.current, ...patch, page: patch.page ?? 0 }
      latest.current = next
      setParams(toCatalogParams(next), { replace: options.replace })
    },
    [setParams],
  )
  const clear = useCallback(() => setParams(new URLSearchParams()), [setParams])

  return { search, update, clear }
}
