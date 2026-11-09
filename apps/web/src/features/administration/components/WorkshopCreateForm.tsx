import { zodResolver } from '@hookform/resolvers/zod'
import { CircleAlert, Info } from 'lucide-react'
import { FormProvider, useForm } from 'react-hook-form'
import { Link } from 'react-router'

import { Button } from '@/shared/ui/button'
import { Notice } from '@/shared/ui/notice'
import { useCreateWorkshop } from '../api/mutations'
import type { Workshop } from '../api/requests'
import { saveErrorView } from '../model/workshop-errors'
import { EMPTY_WORKSHOP_FORM, toCreateRequest, workshopFormSchema, type WorkshopFormValues } from '../model/workshop-form'
import { WorkshopFields } from './WorkshopFields'

/** The create form (S11): a draft is made, then the page for it opens, where the cover and the brochure are added (D160). */
export function WorkshopCreateForm({ onCreated, cancelPath }: { onCreated: (workshop: Workshop) => void; cancelPath: string }) {
  const create = useCreateWorkshop({ onSuccess: onCreated })
  const form = useForm<WorkshopFormValues>({ resolver: zodResolver(workshopFormSchema), defaultValues: EMPTY_WORKSHOP_FORM })
  const error = create.error
  const failure = error ? saveErrorView(error, 'create') : null

  return (
    <FormProvider {...form}>
      <div className="grid items-start gap-6 xl:grid-cols-[minmax(0,1fr)_320px]">
        <form noValidate onSubmit={form.handleSubmit((values) => create.mutate(toCreateRequest(values)))} className="grid gap-5">
          <WorkshopFields mode="create" disabled={create.isPending} />
          {failure && (
            <Notice tone="destructive" icon={CircleAlert} role="alert">
              {failure.message}
              {error?.problem?.correlationId && (
                <span className="mt-0.5 block text-xs">Mã tham chiếu: {error.problem.correlationId}</span>
              )}
            </Notice>
          )}
          <div className="flex flex-wrap items-center gap-2">
            <Button type="submit" disabled={create.isPending}>
              {create.isPending ? 'Đang tạo…' : 'Tạo bản nháp'}
            </Button>
            <Button asChild variant="ghost">
              <Link to={cancelPath}>Bỏ</Link>
            </Button>
          </div>
        </form>

        <aside aria-label="Sau khi tạo" className="grid gap-4 rounded-2xl border border-border bg-card p-5">
          <div>
            <h2 className="text-base leading-6 font-semibold">Sau khi tạo</h2>
            <p className="mt-0.5 text-sm text-muted-foreground">Bạn sẽ chuyển tới trang của bản nháp, nơi làm tiếp ba việc này.</p>
          </div>
          <ol className="grid list-decimal gap-3 pl-5 text-sm">
            <li>
              <b className="font-semibold">Tải ảnh bìa</b>
              <p className="text-xs text-muted-foreground">JPEG, PNG hoặc WebP, tối đa 2 MB. Chưa có ảnh thì thẻ dùng ảnh tự sinh.</p>
            </li>
            <li>
              <b className="font-semibold">Tải brochure PDF</b>
              <p className="text-xs text-muted-foreground">Hệ thống tạo tóm tắt từ brochure.</p>
            </li>
            <li>
              <b className="font-semibold">Công bố</b>
              <p className="text-xs text-muted-foreground">Từ lúc đó sinh viên thấy và đăng ký được. Không đưa về nháp được.</p>
            </li>
          </ol>
          <Notice tone="info" icon={Info}>
            Ảnh và brochure cần workshop đã tồn tại, nên chưa tải ở bước này.
          </Notice>
        </aside>
      </div>
    </FormProvider>
  )
}
