import type { LucideIcon } from 'lucide-react'

import type { Permission } from '@/features/auth'

// What the admin sidebar offers. Each entry names the permission(s) that show it (any one is enough, web-coding-standards.md
// §11 and Rules #13): the sidebar is built from this data, never from a role name. An entry is added together with its route.
//
// It is empty on purpose until Phase 8 Mục 6: "Danh sách workshop" (6.2) and "Tạo workshop" (6.3) have no route yet, and a link
// to a page that does not exist is worse than a sparse sidebar. The report (S15) is per workshop, so it is reached from a
// workshop's row menu, not from here.
export type AdminNavItem = {
  to: string
  label: string
  icon: LucideIcon
  anyOf: readonly Permission[]
}

export const ADMIN_NAV: readonly AdminNavItem[] = []
