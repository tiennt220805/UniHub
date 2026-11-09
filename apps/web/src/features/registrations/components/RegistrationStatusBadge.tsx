import { CircleCheck, CircleX, Clock, TimerOff } from 'lucide-react'

import { Badge } from '@/shared/ui/badge'
import type { RegistrationStatus } from '../api/requests'
import { REGISTRATION_STATUS_LABEL } from '../model/registration-status'

// The tone and the icon of each status (web-design-system.md §5); the words are REGISTRATION_STATUS_LABEL's.
const VIEW = {
  CONFIRMED: { tone: 'success', icon: CircleCheck },
  RESERVED: { tone: 'warning', icon: Clock },
  EXPIRED: { tone: 'muted', icon: TimerOff },
  CANCELLED: { tone: 'destructive', icon: CircleX },
} as const satisfies Record<RegistrationStatus, { tone: string; icon: unknown }>

export function RegistrationStatusBadge({ status }: { status: RegistrationStatus }) {
  const { tone, icon: Icon } = VIEW[status]
  return (
    <Badge tone={tone}>
      <Icon aria-hidden="true" />
      {REGISTRATION_STATUS_LABEL[status]}
    </Badge>
  )
}
