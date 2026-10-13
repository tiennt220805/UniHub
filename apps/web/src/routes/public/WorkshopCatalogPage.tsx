import { useWorkshops, WorkshopSummaryList } from '@/features/workshops'

// listWorkshops is public (paths/workshop.yaml: security: []). No filter UI and no pagination
// controls yet: the server's default page (0, 20) is rendered as is.
export function WorkshopCatalogPage() {
  const workshops = useWorkshops({})

  return (
    <main className="mx-auto max-w-3xl p-6">
      <h1 className="mb-6 text-2xl font-semibold text-foreground">Workshops</h1>
      <WorkshopSummaryList query={workshops} detailPath={(id) => `/workshops/${id}`} />
    </main>
  )
}
