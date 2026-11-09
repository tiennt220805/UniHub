import { ChartColumn } from 'lucide-react'
import { Link, Navigate } from 'react-router'

import { hasAnyPermission, Permission, useSession } from '@/features/auth'
import { Button } from '@/shared/ui/button'
import { EmptyState } from '@/shared/ui/state-panel'

// /admin has no page of its own (D166): it sends the visitor to the first thing their permissions allow. Someone who may
// only read reports has no list to open (listManagedWorkshops needs WORKSHOP_EDIT), so they are told how a report is reached.
export function AdminIndexPage() {
  const session = useSession()
  if (session && hasAnyPermission(session, [Permission.WORKSHOP_EDIT])) return <Navigate to="/admin/workshops" replace />
  if (session && hasAnyPermission(session, [Permission.WORKSHOP_CREATE])) return <Navigate to="/admin/workshops/new" replace />

  return (
    <main className="mx-auto grid w-full max-w-2xl px-6 py-16">
      <EmptyState
        icon={<ChartColumn />}
        title="Tài khoản của bạn chỉ xem được báo cáo"
        description="Báo cáo của từng workshop mở bằng đường dẫn mà người quản lý workshop gửi. Danh sách và tạo workshop cần thêm quyền."
        action={
          <Button asChild variant="outline">
            <Link to="/workshops">Về trang sinh viên</Link>
          </Button>
        }
      />
    </main>
  )
}
