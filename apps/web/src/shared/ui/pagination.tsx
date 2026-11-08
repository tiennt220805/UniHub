import { pageWindow } from "@/shared/lib/page-window"
import { cn } from "@/shared/lib/utils"
import { Button } from "@/shared/ui/button"

type PaginationProps = {
  /** Zero-based, as the API's `page` is; the buttons show it plus one. */
  page: number
  pageCount: number
  onPageChange: (page: number) => void
  className?: string
}

/** Previous, the page numbers and next. Renders nothing for a single page. */
function Pagination({ page, pageCount, onPageChange, className }: PaginationProps) {
  if (pageCount <= 1) return null
  return (
    <nav aria-label="Phân trang" data-slot="pagination" className={cn("flex flex-wrap items-center justify-center gap-1.5", className)}>
      <Button variant="outline" size="sm" disabled={page <= 0} onClick={() => onPageChange(page - 1)}>
        Trước
      </Button>
      {pageWindow(page, pageCount).map((n, i) =>
        n === null ? (
          <span key={`gap-${i}`} aria-hidden="true" className="px-1 text-muted-foreground">
            …
          </span>
        ) : (
          <Button
            key={n}
            variant={n === page ? "default" : "outline"}
            size="sm"
            className="min-w-9"
            aria-current={n === page ? "page" : undefined}
            aria-label={`Trang ${n + 1}`}
            onClick={() => onPageChange(n)}
          >
            {n + 1}
          </Button>
        ),
      )}
      <Button variant="outline" size="sm" disabled={page >= pageCount - 1} onClick={() => onPageChange(page + 1)}>
        Sau
      </Button>
    </nav>
  )
}

export { Pagination }
