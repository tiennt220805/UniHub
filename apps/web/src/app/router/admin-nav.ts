import { List, Plus, type LucideIcon } from 'lucide-react'

import { Permission } from '@/features/auth'

// What the admin sidebar offers. Each entry names the permission(s) that show it (any one is enough, web-coding-standards.md
// §11 and Rules #13): the sidebar is built from this data, never from a role name. An entry is added together with its route.
//
// The report (S15) is per workshop, so it is reached from a workshop's row menu, not from here.
export type AdminNavItem = {
  to: string
  label: string
  icon: LucideIcon
  anyOf: readonly Permission[]
}

export const ADMIN_NAV: readonly AdminNavItem[] = [
  { to: '/admin/workshops', label: 'Danh sách workshop', icon: List, anyOf: [Permission.WORKSHOP_EDIT] },
  { to: '/admin/workshops/new', label: 'Tạo workshop', icon: Plus, anyOf: [Permission.WORKSHOP_CREATE] },
]

// Whoever holds any one of these may enter /admin (S9), and is shown the way in from the student frame. Each page inside still
// asks for its own permission.
export const ADMIN_ACCESS: readonly Permission[] = [Permission.WORKSHOP_CREATE, Permission.WORKSHOP_EDIT, Permission.REPORT_VIEW]
