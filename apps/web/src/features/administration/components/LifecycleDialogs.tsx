import { useQueryClient } from '@tanstack/react-query'
import { CircleAlert, TriangleAlert } from 'lucide-react'

import type { AppError } from '@/shared/api/errors'
import { Notice } from '@/shared/ui/notice'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/shared/ui/alert-dialog'
import { clock, dayHeading, ymdOf } from '@/shared/lib/vn-time'
import { adminKeys } from '../api/keys'
import { useCancelWorkshop, usePublishWorkshop } from '../api/mutations'
import type { Workshop, WorkshopStatus } from '../api/requests'
import { cancelErrorMessage, publishErrorMessage } from '../model/workshop-errors'

const WIDE = 'data-[size=default]:sm:max-w-md'

type LifecycleProps = {
  workshop: { id: string; title: string; status: WorkshopStatus }
  open: boolean
  onOpenChange: (open: boolean) => void
  /** The server's answer, for the cache effects the feature does not own (the catalog, the detail). */
  onDone: (workshop: Workshop) => void
  /** The dialog is closed after a refusal: the route re-reads what it shows (the lists here are re-read by the dialog itself). */
  onSettled?: () => void
}

/**
 * What to do when the user closes a dialog that showed a refusal: read the lists again now, not when the answer came (a 409 means
 * the status has already moved, and the row the dialog belongs to may leave the tab the user is on).
 */
function useRefreshAfterRefusal(onSettled?: () => void) {
  const queryClient = useQueryClient()
  return () => {
    void queryClient.invalidateQueries({ queryKey: adminKeys.managedLists() })
    onSettled?.()
  }
}

function Failure({ error, message }: { error: AppError; message: string }) {
  return (
    <Notice tone="destructive" icon={CircleAlert} role="alert">
      {message}
      {error.problem?.correlationId && <span className="mt-0.5 block text-xs">Mã tham chiếu: {error.problem.correlationId}</span>}
    </Notice>
  )
}

/** "Công bố workshop?" (6.5): one way, from a draft to what students see. */
export function PublishWorkshopDialog({ workshop, open, onOpenChange, onDone, onSettled }: LifecycleProps) {
  const publish = usePublishWorkshop(workshop.id, {
    onSuccess: (published) => {
      onOpenChange(false)
      onDone(published)
    },
  })
  const refresh = useRefreshAfterRefusal(onSettled)
  return (
    <AlertDialog
      open={open}
      onOpenChange={(next) => {
        if (publish.isPending) return
        if (!next) {
          if (publish.isError) refresh()
          publish.reset()
        }
        onOpenChange(next)
      }}
    >
      <AlertDialogContent className={WIDE}>
        <AlertDialogHeader>
          <AlertDialogTitle>Công bố workshop?</AlertDialogTitle>
          <AlertDialogDescription>
            “{workshop.title}”: sinh viên sẽ thấy workshop trong danh sách và đăng ký được ngay. Bạn vẫn sửa được thông tin sau khi công bố,
            nhưng không đưa nó về nháp được.
          </AlertDialogDescription>
        </AlertDialogHeader>
        {publish.isError && <Failure error={publish.error} message={publishErrorMessage(publish.error)} />}
        <AlertDialogFooter>
          <AlertDialogCancel disabled={publish.isPending}>Để sau</AlertDialogCancel>
          <AlertDialogAction
            disabled={publish.isPending}
            onClick={(event) => {
              event.preventDefault() // stay open until the server has answered
              publish.mutate()
            }}
          >
            {publish.isPending ? 'Đang xử lý…' : 'Công bố'}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}

/**
 * "Hủy workshop này?" (6.5, D157). The words say only what the backend does today (F2, F8): the status changes and the students
 * holding a seat are told; their registrations are not cancelled, their seats are not given back and nothing is refunded.
 */
export function CancelWorkshopDialog({ workshop, open, onOpenChange, onDone, onSettled }: LifecycleProps) {
  const cancel = useCancelWorkshop(workshop.id, {
    onSuccess: (cancelled) => {
      onOpenChange(false)
      onDone(cancelled)
    },
  })
  const refresh = useRefreshAfterRefusal(onSettled)
  const isDraft = workshop.status === 'DRAFT'
  return (
    <AlertDialog
      open={open}
      onOpenChange={(next) => {
        if (cancel.isPending) return
        if (!next) {
          if (cancel.isError) refresh()
          cancel.reset()
        }
        onOpenChange(next)
      }}
    >
      <AlertDialogContent className={WIDE}>
        <AlertDialogHeader>
          <AlertDialogTitle>{isDraft ? 'Hủy bản nháp này?' : 'Hủy workshop này?'}</AlertDialogTitle>
          <AlertDialogDescription asChild>
            <div className="grid gap-3 text-left">
              <p>“{workshop.title}”</p>
              {isDraft ? (
                <p>
                  Chưa sinh viên nào thấy workshop này, nên không có thông báo nào được gửi. Bản nháp chuyển sang “Đã hủy”, nằm ở tab Đã hủy và
                  không sửa được nữa.
                </p>
              ) : (
                <>
                  <ul className="list-disc space-y-1 pl-5">
                    <li>Workshop chuyển sang “Đã hủy” và không sửa được nữa.</li>
                    <li>Sinh viên đang giữ chỗ hoặc đã đăng ký nhận email và thông báo trong hộp thư.</li>
                  </ul>
                  <Notice tone="warning" icon={TriangleAlert}>
                    <b className="font-semibold">Đăng ký hiện có không bị hủy tự động.</b> Chỗ chưa được trả lại và hệ thống chưa tự hoàn tiền
                    cho đăng ký đã thanh toán.
                  </Notice>
                </>
              )}
            </div>
          </AlertDialogDescription>
        </AlertDialogHeader>
        {cancel.isError && <Failure error={cancel.error} message={cancelErrorMessage(cancel.error)} />}
        <AlertDialogFooter>
          <AlertDialogCancel disabled={cancel.isPending}>{isDraft ? 'Giữ bản nháp' : 'Giữ workshop'}</AlertDialogCancel>
          <AlertDialogAction
            variant="destructive"
            disabled={cancel.isPending}
            onClick={(event) => {
              event.preventDefault()
              cancel.mutate()
            }}
          >
            {cancel.isPending ? 'Đang xử lý…' : isDraft ? 'Hủy bản nháp' : 'Hủy workshop'}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}

export type TimeRange = { start: string; end: string }

const describeRange = ({ start, end }: TimeRange) => `${dayHeading(ymdOf(start))} · ${clock(start)}–${clock(end)}`

/** "Đổi lịch workshop?" (D156): shown only when a published workshop's time changes; the students are told when it is saved. */
export function RescheduleDialog({
  open,
  onOpenChange,
  current,
  next,
  pending,
  onConfirm,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  current: TimeRange
  next: TimeRange
  pending: boolean
  onConfirm: () => void
}) {
  return (
    <AlertDialog open={open} onOpenChange={(value) => !pending && onOpenChange(value)}>
      <AlertDialogContent className={WIDE}>
        <AlertDialogHeader>
          <AlertDialogTitle>Đổi lịch workshop?</AlertDialogTitle>
          <AlertDialogDescription asChild>
            <div className="grid gap-3 text-left">
              <dl className="grid grid-cols-[auto_1fr] gap-x-3 gap-y-1 rounded-lg bg-muted p-3 text-sm text-foreground">
                <dt className="text-muted-foreground">Giờ hiện tại</dt>
                <dd className="font-medium">{describeRange(current)}</dd>
                <dt className="text-muted-foreground">Giờ mới</dt>
                <dd className="font-medium">{describeRange(next)}</dd>
              </dl>
              <p>Sinh viên đang giữ chỗ hoặc đã đăng ký sẽ nhận email và thông báo trong hộp thư về giờ mới.</p>
            </div>
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel disabled={pending}>Xem lại</AlertDialogCancel>
          <AlertDialogAction
            disabled={pending}
            onClick={(event) => {
              event.preventDefault()
              onConfirm()
            }}
          >
            {pending ? 'Đang xử lý…' : 'Đổi lịch và gửi thông báo'}
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
