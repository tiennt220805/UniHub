import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'

import { formatDateTime } from '@/lib/format'
import type { components } from '@/types/schema.gen'

type WorkshopListResponse = components['schemas']['WorkshopListResponse']

// GET /api/v1/workshops is public (packages/api-contract/paths/workshop.yaml: security: []).
// No filters, no interactive pagination yet — the server's default page (0, 20) is rendered as is.
export function WorkshopCatalogPage() {
  const { data, isPending, isError } = useQuery({
    queryKey: ['workshops', 'list'],
    queryFn: async (): Promise<WorkshopListResponse> => {
      const response = await fetch('/api/v1/workshops')
      if (!response.ok) {
        throw new Error(`GET /api/v1/workshops failed: ${response.status}`)
      }
      return response.json()
    },
  })

  return (
    <main className="mx-auto max-w-3xl p-6">
      <h1 className="mb-6 text-2xl font-semibold text-foreground">Workshops</h1>
      {isPending && <p>Đang tải…</p>}
      {isError && <p role="alert">Không tải được danh sách workshop.</p>}
      {data && data.items.length === 0 && <p>Chưa có workshop nào.</p>}
      {data && data.items.length > 0 && (
        <ul className="divide-y divide-border">
          {data.items.map((workshop) => (
            <li key={workshop.id} className="py-4">
              <Link
                to={`/workshops/${workshop.id}`}
                className="text-lg font-medium text-foreground hover:underline"
              >
                {workshop.title}
              </Link>
              <p className="text-sm text-muted-foreground">
                <time dateTime={workshop.startTime}>{formatDateTime(workshop.startTime)}</time>
                {' · '}
                {workshop.location}
              </p>
            </li>
          ))}
        </ul>
      )}
    </main>
  )
}
