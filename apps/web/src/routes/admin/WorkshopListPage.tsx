import { Plus } from 'lucide-react'
import { Link } from 'react-router'

import { hasAnyPermission, Permission, useSession } from '@/features/auth'
import { ManagedWorkshopList } from '@/features/administration'
import { WorkshopCover } from '@/features/workshops'
import { Button } from '@/shared/ui/button'

// listManagedWorkshops needs WORKSHOP_EDIT (the route is guarded by it). "Tạo workshop" shows only for someone who may also
// create: a role can hold one permission without the other (web-coding-standards.md §11, Rules #13).
export function WorkshopListPage() {
  const session = useSession()
  const canCreate = session !== null && hasAnyPermission(session, [Permission.WORKSHOP_CREATE])

  return (
    <main className="mx-auto grid w-full max-w-7xl gap-5 px-6 py-8 lg:px-8">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div>
          <h1 className="text-3xl leading-[38px] font-bold text-foreground">Workshop</h1>
          <p className="mt-1 text-muted-foreground">Mọi trạng thái, kể cả bản nháp. Sắp theo giờ bắt đầu, mới nhất trước.</p>
        </div>
        {canCreate && (
          <Button asChild>
            <Link to="/admin/workshops/new">
              <Plus aria-hidden="true" />
              Tạo workshop
            </Link>
          </Button>
        )}
      </div>
      <ManagedWorkshopList
        workshopPath={(id) => `/admin/workshops/${id}`}
        createPath={canCreate ? '/admin/workshops/new' : undefined}
        renderCover={(workshop) => <WorkshopCover workshopId={workshop.id} coverImageUrl={workshop.coverImageUrl ?? null} />}
      />
    </main>
  )
}
