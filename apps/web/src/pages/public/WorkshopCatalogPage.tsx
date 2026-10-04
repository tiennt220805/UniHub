import { useQuery } from '@tanstack/react-query'
import type { components } from '@/types/schema.gen'

type WorkshopListResponse = components['schemas']['WorkshopListResponse']

// GET /api/v1/workshops is public (packages/api-contract/paths/workshop.yaml: security: []).
// Walking-skeleton slice (Phase 1 Mục 1): no filters, no interactive pagination — the server's
// default page (0, 20) is enough to prove the data path end to end.
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
    <main>
      <h1>Workshops</h1>
      {isPending && <p>Đang tải…</p>}
      {isError && <p role="alert">Không tải được danh sách workshop.</p>}
      {data && data.items.length === 0 && <p>Chưa có workshop nào.</p>}
      {data && data.items.length > 0 && (
        <ul>
          {data.items.map((workshop) => (
            <li key={workshop.id}>
              {workshop.title} — {workshop.location}
            </li>
          ))}
        </ul>
      )}
    </main>
  )
}
