import { SlidersHorizontal } from 'lucide-react'
import { useState } from 'react'

import { useDraft } from '@/shared/hooks/use-draft'
import { cn } from '@/shared/lib/utils'
import { Button } from '@/shared/ui/button'
import { Field, FieldError, Label } from '@/shared/ui/field'
import { Input } from '@/shared/ui/input'
import { activeFilterCount, type CatalogSearch } from '../model/catalog-filters'

type Props = {
  search: CatalogSearch
  /** `YYYY-MM-DD` of today in Vietnam: what the "Từ ngày" box shows when the URL has no `dateFrom` (D138). */
  today: string
  /** The range is backwards, caught here or answered 400 by the server. */
  rangeError: boolean
  onChange: (patch: Partial<CatalogSearch>, options?: { replace?: boolean }) => void
  onClear: () => void
}

export function CatalogFilters({ search, today, rangeError, onChange, onClear }: Props) {
  const [expanded, setExpanded] = useState(false)
  const count = activeFilterCount(search)
  const [q, setQ] = useDraft(search.q, (next) => onChange({ q: next }, { replace: true }))
  const [speaker, setSpeaker] = useDraft(search.speaker, (next) => onChange({ speaker: next }, { replace: true }))
  const [location, setLocation] = useDraft(search.location, (next) => onChange({ location: next }, { replace: true }))

  return (
    <form
      aria-label="Bộ lọc workshop"
      onSubmit={(event) => event.preventDefault()}
      className="grid gap-4 rounded-xl border border-border bg-card p-4 shadow-sm md:grid-cols-3 md:p-5 dark:shadow-none"
    >
      <Field>
        <Label htmlFor="catalog-q">Chủ đề</Label>
        <Input id="catalog-q" type="search" enterKeyHint="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Ví dụ: Kubernetes" className="h-11" />
      </Field>

      <Button
        type="button"
        variant="outline"
        size="lg"
        className="md:hidden"
        aria-expanded={expanded}
        aria-controls="catalog-more-filters"
        onClick={() => setExpanded((open) => !open)}
      >
        <SlidersHorizontal aria-hidden="true" />
        {count > 0 ? `Bộ lọc · ${count}` : 'Bộ lọc'}
      </Button>

      {/* One set of fields: below `md` it opens from the button above, from `md` it is always laid out in the grid. */}
      <div id="catalog-more-filters" className={cn('grid gap-4', expanded ? '' : 'hidden', 'md:contents')}>
        <Field>
          <Label htmlFor="catalog-speaker">Diễn giả</Label>
          <Input id="catalog-speaker" value={speaker} onChange={(e) => setSpeaker(e.target.value)} placeholder="Tên diễn giả" className="h-11" />
        </Field>
        <Field>
          <Label htmlFor="catalog-location">Địa điểm</Label>
          <Input id="catalog-location" value={location} onChange={(e) => setLocation(e.target.value)} placeholder="Phòng, tòa nhà" className="h-11" />
        </Field>
        <Field>
          <Label htmlFor="catalog-from">Từ ngày</Label>
          <Input
            id="catalog-from"
            type="date"
            value={search.dateFrom || today}
            onChange={(e) => onChange({ dateFrom: e.target.value })}
            aria-invalid={rangeError || undefined}
            aria-describedby={rangeError ? 'catalog-range-error' : undefined}
            className="h-11"
          />
          {rangeError && (
            <FieldError id="catalog-range-error" role="alert">
              &quot;Từ ngày&quot; phải trước hoặc bằng &quot;Đến ngày&quot;.
            </FieldError>
          )}
        </Field>
        <Field>
          <Label htmlFor="catalog-to">Đến ngày</Label>
          <Input
            id="catalog-to"
            type="date"
            value={search.dateTo}
            onChange={(e) => onChange({ dateTo: e.target.value })}
            aria-invalid={rangeError || undefined}
            className="h-11"
          />
        </Field>
        <div className="flex items-end md:justify-end">
          {count > 0 && (
            <Button type="button" variant="link" size="lg" onClick={onClear}>
              Xóa bộ lọc
            </Button>
          )}
        </div>
      </div>
    </form>
  )
}
