import { WorkshopCatalog } from '@/features/workshops'

// listWorkshops is public (paths/workshop.yaml: security: []). The filters, the day grouping and the pagination are in
// the feature; the route table owns where a card links to.
export function WorkshopCatalogPage() {
  return (
    <main className="mx-auto max-w-6xl px-4 py-8 md:px-6 md:py-10">
      <h1 className="text-3xl leading-[38px] font-bold text-foreground">Workshops</h1>
      <p className="mt-1 mb-6 text-muted-foreground">Các workshop sắp diễn ra, theo ngày.</p>
      <WorkshopCatalog detailPath={(id) => `/workshops/${id}`} />
    </main>
  )
}
