import { zodResolver } from '@hookform/resolvers/zod'
import { CircleAlert, Info, RefreshCw } from 'lucide-react'
import { useState } from 'react'
import { FormProvider, useForm } from 'react-hook-form'

import { formatNumber } from '@/shared/lib/format'
import { fromDateTimeLocal } from '@/shared/lib/vn-time'
import { Button } from '@/shared/ui/button'
import { Notice } from '@/shared/ui/notice'
import { useUpdateWorkshop } from '../api/mutations'
import type { Workshop } from '../api/requests'
import { saveErrorView } from '../model/workshop-errors'
import { changesSchedule, toFormValues, toUpdateRequest, workshopFormSchema, type WorkshopFormValues } from '../model/workshop-form'
import { isTerminal } from '../model/workshop-status'
import { RescheduleDialog } from './LifecycleDialogs'
import { WorkshopFields } from './WorkshopFields'

type Props = {
  /** The server's current copy. The form loads it once; a refetch (window focus) never rewrites what is being typed. */
  workshop: Workshop
  /** The session holds WORKSHOP_EDIT. Without it the fields are read-only (D166). */
  canEdit: boolean
  /** The server's answer to a save, for the cache effects the feature does not own (the catalog, the detail). */
  onSaved: (workshop: Workshop) => void
  /** Reads the workshop again; used by "Tải lại" after a 409. */
  onReload: () => Promise<Workshop | undefined>
}

/**
 * The edit form (S12). PATCH sends only the fields changed (D155: the contract has no `version`). Changing the time of a
 * published workshop asks first (D156), because saving tells the students who hold a seat.
 */
export function WorkshopEditor({ workshop, canEdit, onSaved, onReload }: Props) {
  const form = useForm<WorkshopFormValues>({ resolver: zodResolver(workshopFormSchema), defaultValues: toFormValues(workshop) })
  const update = useUpdateWorkshop(workshop.id, {
    onSuccess: (saved) => {
      form.reset(toFormValues(saved)) // the saved copy is the new "unchanged"
      setConfirming(false)
      onSaved(saved)
    },
  })
  const { dirtyFields, isDirty } = form.formState
  const [confirming, setConfirming] = useState(false)
  const [reloading, setReloading] = useState(false)

  const readOnly = !canEdit || isTerminal(workshop.status)
  const failure = update.isError ? saveErrorView(update.error, 'update') : null
  const scheduleChanged = changesSchedule(dirtyFields)
  const asksFirst = scheduleChanged && workshop.status === 'PUBLISHED'

  const save = (values: WorkshopFormValues) => {
    // A capacity under the seats taken is known to be refused: say so at the field (the figure may be a few seconds old, so the
    // server still decides).
    if (dirtyFields.capacity && Number(values.capacity) < workshop.seatsTaken) {
      form.setError('capacity', { message: `Sức chứa không thể thấp hơn số chỗ đã giữ (${formatNumber(workshop.seatsTaken)}).` })
      return
    }
    const body = toUpdateRequest(values, dirtyFields, workshop)
    if (Object.keys(body).length === 0) return
    if (asksFirst) {
      setConfirming(true)
      return
    }
    update.mutate(body)
  }
  const confirmedSave = () => {
    const values = form.getValues()
    update.mutate(toUpdateRequest(values, dirtyFields, workshop))
  }

  const reload = async () => {
    setReloading(true)
    try {
      const fresh = await onReload()
      if (fresh) form.reset(toFormValues(fresh))
      update.reset()
    } finally {
      setReloading(false)
    }
  }

  const values = form.getValues()
  const next = { start: fromDateTimeLocal(values.startTime), end: fromDateTimeLocal(values.endTime) }

  return (
    <FormProvider {...form}>
      <form noValidate onSubmit={form.handleSubmit(save)} className="grid gap-5" aria-label="Thông tin workshop">
        {readOnly && (
          <Notice tone={isTerminal(workshop.status) ? 'destructive' : 'info'} icon={isTerminal(workshop.status) ? CircleAlert : Info}>
            {isTerminal(workshop.status)
              ? `Workshop này đã ${workshop.status === 'CANCELLED' ? 'bị hủy' : 'kết thúc'} nên không sửa được.`
              : 'Bạn có quyền tạo workshop nhưng không có quyền sửa, công bố hay hủy.'}
          </Notice>
        )}
        <fieldset disabled={readOnly || update.isPending} className="m-0 min-w-0 border-0 p-0">
          <WorkshopFields
            mode="edit"
            disabled={readOnly || update.isPending}
            seatsTaken={workshop.seatsTaken}
            isPaid={workshop.isPaid}
            scheduleNotice={
              asksFirst ? (
                <Notice tone="info" icon={Info}>
                  <b className="font-semibold">Bạn đã đổi giờ.</b> Khi lưu, sinh viên đang giữ chỗ hoặc đã đăng ký sẽ nhận thông báo về giờ mới.
                </Notice>
              ) : null
            }
          />
        </fieldset>

        {failure && (
          <Notice tone="destructive" icon={CircleAlert} role="alert">
            {failure.message}
            {update.error?.problem?.correlationId && (
              <span className="mt-0.5 block text-xs">Mã tham chiếu: {update.error.problem.correlationId}</span>
            )}
            {failure.offersReload && (
              <div>
                <Button type="button" variant="outline" size="sm" className="mt-2" onClick={() => void reload()} disabled={reloading}>
                  <RefreshCw aria-hidden="true" />
                  Tải lại
                </Button>
              </div>
            )}
          </Notice>
        )}

        {!readOnly && (
          <div className="sticky bottom-4 z-10 flex flex-wrap items-center justify-between gap-3 rounded-xl border border-border bg-card p-3 shadow-lg dark:shadow-none">
            <span className="flex items-center gap-2 text-sm text-muted-foreground" aria-live="polite">
              <Info aria-hidden="true" className="size-4" />
              {isDirty ? 'Có thay đổi chưa lưu' : 'Chưa có thay đổi'}
            </span>
            <span className="flex items-center gap-2">
              <Button type="button" variant="ghost" disabled={!isDirty || update.isPending} onClick={() => form.reset(toFormValues(workshop))}>
                Bỏ thay đổi
              </Button>
              <Button type="submit" disabled={!isDirty || update.isPending}>
                {update.isPending && !confirming ? 'Đang lưu…' : 'Lưu thay đổi'}
              </Button>
            </span>
          </div>
        )}
      </form>

      {next.start && next.end && (
        <RescheduleDialog
          open={confirming}
          onOpenChange={setConfirming}
          current={{ start: workshop.startTime, end: workshop.endTime }}
          next={{ start: next.start, end: next.end }}
          pending={update.isPending}
          onConfirm={confirmedSave}
        />
      )}
    </FormProvider>
  )
}
