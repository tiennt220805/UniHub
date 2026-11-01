import { Link, useParams } from 'react-router'

import { useWorkshopReport, WorkshopReportView } from '@/features/reports'

// getWorkshopReport needs REPORT_VIEW; the route is behind RequirePermission, which is UX only: the
// Backend API is the enforcement point (web-coding-standards.md §11).
export function WorkshopReportPage() {
  const { workshopId = '' } = useParams()
  const report = useWorkshopReport(workshopId)

  return (
    <main className="mx-auto max-w-3xl space-y-4 p-6">
      <Link to={`/workshops/${workshopId}`} className="text-sm text-muted-foreground hover:underline">
        ← Chi tiết workshop
      </Link>
      <h1 className="text-2xl font-semibold text-foreground">Báo cáo workshop</h1>
      <WorkshopReportView query={report} />
    </main>
  )
}
