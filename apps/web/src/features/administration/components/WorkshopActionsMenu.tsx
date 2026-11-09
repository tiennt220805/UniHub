import { ChartColumn, EllipsisVertical, Globe, Pencil, Ban } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'

import { Button } from '@/shared/ui/button'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger } from '@/shared/ui/dropdown-menu'
import type { ManagedWorkshop, Workshop } from '../api/requests'
import { isTerminal } from '../model/workshop-status'
import { CancelWorkshopDialog, PublishWorkshopDialog } from './LifecycleDialogs'

type Props = {
  workshop: Pick<ManagedWorkshop, 'id' | 'title' | 'status'>
  editPath: string
  /** Present only for someone holding REPORT_VIEW. */
  reportPath?: string
  /** Publish and cancel are WORKSHOP_EDIT's; the list itself already needs it, but the rule is stated where the buttons are. */
  canEdit: boolean
  onDone: (workshop: Workshop) => void
  onSettled?: () => void
}

type Dialog = 'publish' | 'cancel' | null

/** A row's "⋯" menu (D166): edit, the report, and the two one-way changes, each behind its confirmation (6.5). */
export function WorkshopActionsMenu({ workshop, editPath, reportPath, canEdit, onDone, onSettled }: Props) {
  const [dialog, setDialog] = useState<Dialog>(null)
  const canPublish = canEdit && workshop.status === 'DRAFT'
  const canCancel = canEdit && !isTerminal(workshop.status)
  const hasChanges = canPublish || canCancel

  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="ghost" size="icon-sm" aria-label={`Thao tác với ${workshop.title}`}>
            <EllipsisVertical aria-hidden="true" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end" className="w-56">
          <DropdownMenuItem asChild>
            <Link to={editPath}>
              <Pencil aria-hidden="true" />
              {isTerminal(workshop.status) ? 'Xem thông tin' : 'Sửa thông tin'}
            </Link>
          </DropdownMenuItem>
          {reportPath && (
            <DropdownMenuItem asChild>
              <Link to={reportPath}>
                <ChartColumn aria-hidden="true" />
                Xem báo cáo
              </Link>
            </DropdownMenuItem>
          )}
          {hasChanges && <DropdownMenuSeparator />}
          {canPublish && (
            <DropdownMenuItem onSelect={() => setDialog('publish')}>
              <Globe aria-hidden="true" />
              Công bố
            </DropdownMenuItem>
          )}
          {canCancel && (
            <DropdownMenuItem variant="destructive" onSelect={() => setDialog('cancel')}>
              <Ban aria-hidden="true" />
              {workshop.status === 'DRAFT' ? 'Hủy bản nháp' : 'Hủy workshop'}
            </DropdownMenuItem>
          )}
        </DropdownMenuContent>
      </DropdownMenu>
      {canPublish && (
        <PublishWorkshopDialog
          workshop={workshop}
          open={dialog === 'publish'}
          onOpenChange={(open) => setDialog(open ? 'publish' : null)}
          onDone={onDone}
          onSettled={onSettled}
        />
      )}
      {canCancel && (
        <CancelWorkshopDialog
          workshop={workshop}
          open={dialog === 'cancel'}
          onOpenChange={(open) => setDialog(open ? 'cancel' : null)}
          onDone={onDone}
          onSettled={onSettled}
        />
      )}
    </>
  )
}
