import { Link, useNavigate } from 'react-router'

import { WorkshopCreateForm } from '@/features/administration'
import { useWorkshopCache } from './use-workshop-cache'

// createWorkshop needs WORKSHOP_CREATE (the route is guarded by it). The new workshop is a DRAFT; the page that opens next is where
// its cover and brochure are added (D160).
export function WorkshopCreatePage() {
  const navigate = useNavigate()
  const cache = useWorkshopCache()

  return (
    <main className="mx-auto grid w-full max-w-7xl gap-5 px-6 py-8 lg:px-8">
      <nav aria-label="Vị trí trang" className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
        <Link to="/admin/workshops" className="hover:underline">
          Workshop
        </Link>
        <span aria-hidden="true">/</span>
        <span aria-current="page">Tạo workshop</span>
      </nav>
      <div>
        <h1 className="text-3xl leading-[38px] font-bold text-foreground">Tạo workshop</h1>
        <p className="mt-1 text-muted-foreground">Workshop mới ở trạng thái Nháp. Sinh viên chưa thấy nó cho đến khi bạn công bố.</p>
      </div>
      <WorkshopCreateForm
        cancelPath="/admin/workshops"
        onCreated={(workshop) => {
          cache.wrote(workshop)
          void navigate(`/admin/workshops/${workshop.id}`, { state: { created: true } })
        }}
      />
    </main>
  )
}
