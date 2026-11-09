import { Pencil } from 'lucide-react'
import { Link, useParams, useSearchParams } from 'react-router'

import { hasAnyPermission, Permission, useSession } from '@/features/auth'
import { AttendanceList, useWorkshopReport, WorkshopReportView } from '@/features/reports'
import { useWorkshop } from '@/features/workshops'
import { formatDateTime } from '@/shared/lib/format'
import { Button } from '@/shared/ui/button'

const pageOf = (params: URLSearchParams) => {
  const page = Number(params.get('page'))
  return Number.isInteger(page) && page > 0 ? page : 0
}

// getWorkshopReport and listWorkshopAttendances need REPORT_VIEW; the route is behind RequirePermission, which is UX only: the
// Backend API is the enforcement point (web-coding-standards.md §11).
export function WorkshopReportPage() {
  const { workshopId = '' } = useParams()
  const report = useWorkshopReport(workshopId)
  // The title is a courtesy: a REPORT_VIEW session without WORKSHOP_CREATE/EDIT is answered 404 for a draft, and the report must
  // still show. Its failure draws nothing.
  const workshop = useWorkshop(workshopId).data
  const session = useSession()
  const canEdit = session !== null && hasAnyPermission(session, [Permission.WORKSHOP_EDIT])
  const [params, setParams] = useSearchParams()

  return (
    <main className="mx-auto grid w-full max-w-7xl gap-5 px-6 py-8 lg:px-8">
      <nav aria-label="Vị trí trang" className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
        {canEdit && (
          <>
            <Link to="/admin/workshops" className="hover:underline">
              Workshop
            </Link>
            <span aria-hidden="true">/</span>
            {workshop && (
              <>
                <Link to={`/admin/workshops/${workshopId}`} className="hover:underline">
                  {workshop.title}
                </Link>
                <span aria-hidden="true">/</span>
              </>
            )}
          </>
        )}
        <span aria-current="page" className="text-foreground">
          Báo cáo
        </span>
      </nav>
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-3xl leading-[38px] font-bold text-foreground">Báo cáo workshop</h1>
          {workshop && (
            <p className="mt-1 text-muted-foreground">
              {workshop.title} · {formatDateTime(workshop.startTime)}
            </p>
          )}
        </div>
        {canEdit && (
          <Button asChild variant="outline">
            <Link to={`/admin/workshops/${workshopId}`}>
              <Pencil aria-hidden="true" />
              Sửa workshop
            </Link>
          </Button>
        )}
      </div>
      <WorkshopReportView query={report} />
      {report.data && (
        <AttendanceList
          workshopId={workshopId}
          page={pageOf(params)}
          onPageChange={(page) => setParams(page > 0 ? { page: String(page) } : {}, { replace: false })}
          checkedInCount={report.data.checkedInCount}
        />
      )}
    </main>
  )
}
