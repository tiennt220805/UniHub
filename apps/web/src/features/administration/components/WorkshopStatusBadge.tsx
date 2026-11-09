import { CircleX, Flag, Globe, Pencil } from 'lucide-react'

import { Badge } from '@/shared/ui/badge'
import type { WorkshopStatus } from '../api/requests'
import { WORKSHOP_STATUS } from '../model/workshop-status'

// The icon of each status (web-design-system.md §5): a status never rides on colour alone.
const ICON = { DRAFT: Pencil, PUBLISHED: Globe, CANCELLED: CircleX, COMPLETED: Flag } as const satisfies Record<WorkshopStatus, unknown>

export function WorkshopStatusBadge({ status }: { status: WorkshopStatus }) {
  const Icon = ICON[status]
  const { label, tone } = WORKSHOP_STATUS[status]
  return (
    <Badge tone={tone}>
      <Icon aria-hidden="true" />
      {label}
    </Badge>
  )
}

/** A published workshop that has already ended (D158). */
export function EndedBadge() {
  return (
    <Badge tone="muted">
      <Flag aria-hidden="true" />
      Đã diễn ra
    </Badge>
  )
}
