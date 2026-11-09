import { ChartColumn, ExternalLink } from 'lucide-react'
import { Link, useLocation, useParams } from 'react-router'

import { BrochureCard, CoverImageCard, isTerminal, WorkshopEditor, WorkshopStatusBadge, WorkshopStatusCard } from '@/features/administration'
import { hasAnyPermission, Permission, useSession } from '@/features/auth'
import { useWorkshop } from '@/features/workshops'
import { LoadError } from '@/shared/components/LoadError'
import { Button } from '@/shared/ui/button'
import { Notice } from '@/shared/ui/notice'
import { Skeleton } from '@/shared/ui/skeleton'
import { toast } from '@/shared/store/toast-store'
import { CheckCheck } from 'lucide-react'
import { useWorkshopCache } from './use-workshop-cache'

function errorMessage(status: number | null): string {
  if (status === 404) return 'Không tìm thấy workshop này.'
  if (status === 403) return 'Bạn không có quyền xem workshop này.'
  return 'Không tải được workshop.'
}

// The route is let in by WORKSHOP_CREATE or WORKSHOP_EDIT; the fields are editable only with WORKSHOP_EDIT, and publish and cancel
// are shown only with it (D166). getWorkshop answers a manager from the Master, so the seats here are not a cache's.
export function WorkshopEditPage() {
  const { workshopId = '' } = useParams()
  const query = useWorkshop(workshopId)
  const session = useSession()
  const location = useLocation()
  const cache = useWorkshopCache()
  const canEdit = session !== null && hasAnyPermission(session, [Permission.WORKSHOP_EDIT])
  const canUpload = session !== null && hasAnyPermission(session, [Permission.WORKSHOP_CREATE, Permission.WORKSHOP_EDIT])
  const canReport = session !== null && hasAnyPermission(session, [Permission.REPORT_VIEW])
  const justCreated = (location.state as { created?: boolean } | null)?.created === true
  const workshop = query.data

  return (
    <main className="mx-auto grid w-full max-w-7xl gap-5 px-6 py-8 lg:px-8">
      <nav aria-label="Vị trí trang" className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
        {canEdit && (
          <>
            <Link to="/admin/workshops" className="hover:underline">
              Workshop
            </Link>
            <span aria-hidden="true">/</span>
          </>
        )}
        <span aria-current="page" className="min-w-0 text-foreground">
          {workshop?.title ?? 'Workshop'}
        </span>
      </nav>

      {query.isPending ? (
        <div aria-busy="true" className="grid gap-4">
          <p role="status" className="sr-only">
            Đang tải workshop…
          </p>
          <Skeleton className="h-9 w-2/3" />
          <Skeleton className="h-64 w-full rounded-2xl" />
        </div>
      ) : workshop === undefined ? (
        <LoadError error={query.error} message={errorMessage(query.error.status)} onRetry={() => void query.refetch()} />
      ) : (
        <>
          <div className="flex flex-wrap items-start justify-between gap-4">
            <div className="flex min-w-0 flex-wrap items-center gap-3">
              <h1 className="min-w-0 text-3xl leading-[38px] font-bold text-foreground">{workshop.title}</h1>
              <WorkshopStatusBadge status={workshop.status} />
            </div>
            <div className="flex flex-wrap items-center gap-2">
              {canReport && (
                <Button asChild variant="outline">
                  <Link to={`/admin/workshops/${workshop.id}/report`}>
                    <ChartColumn aria-hidden="true" />
                    Xem báo cáo
                  </Link>
                </Button>
              )}
              <Button asChild variant="ghost">
                <Link to={`/workshops/${workshop.id}`}>
                  <ExternalLink aria-hidden="true" />
                  Xem như sinh viên
                </Link>
              </Button>
            </div>
          </div>
          {justCreated && (
            <Notice tone="success" icon={CheckCheck} role="status">
              Đã tạo bản nháp. Thêm ảnh bìa và brochure, rồi công bố khi sẵn sàng.
            </Notice>
          )}
          <div className="grid items-start gap-6 xl:grid-cols-[minmax(0,1fr)_340px]">
            <WorkshopEditor
              key={workshop.id}
              workshop={workshop}
              canEdit={canEdit}
              onSaved={(saved) => {
                cache.wrote(saved)
                toast.success('Đã lưu thay đổi.')
              }}
              onReload={async () => (await query.refetch()).data}
            />
            <aside aria-label="Trạng thái, ảnh bìa và brochure" className="grid gap-4">
              <WorkshopStatusCard
                workshop={workshop}
                canEdit={canEdit}
                onDone={(changed) => {
                  cache.wrote(changed)
                  toast.success(changed.status === 'PUBLISHED' ? 'Đã công bố workshop.' : 'Đã hủy workshop.')
                }}
                onSettled={() => cache.reread(workshop.id)}
              />
              {/* Cover and brochure are uploaded with either permission, but never to a cancelled or completed workshop. */}
              <CoverImageCard
                key={`cover-${workshop.id}`}
                workshopId={workshop.id}
                coverImageUrl={workshop.coverImageUrl ?? null}
                canUpload={canUpload && !isTerminal(workshop.status)}
                onUploaded={() => cache.reread(workshop.id)}
              />
              <BrochureCard
                key={`brochure-${workshop.id}`}
                workshopId={workshop.id}
                hasBrochure={workshop.hasBrochure}
                aiSummary={workshop.aiSummary ?? null}
                canUpload={canUpload && !isTerminal(workshop.status)}
                onUploaded={() => cache.reread(workshop.id)}
                onRecheck={() => void query.refetch()}
                rechecking={query.isFetching}
              />
            </aside>
          </div>
        </>
      )}
    </main>
  )
}
