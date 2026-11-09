import type { ReactNode } from 'react'
import { useFormContext } from 'react-hook-form'

import { formatNumber } from '@/shared/lib/format'
import { Field, FieldError, FieldHint, Label } from '@/shared/ui/field'
import { Input, Textarea } from '@/shared/ui/input'
import type { WorkshopFormValues } from '../model/workshop-form'

type Props = {
  mode: 'create' | 'edit'
  /** The workshop cannot be changed by this session, or any more (cancelled, completed). */
  disabled?: boolean
  /** Edit only: the seats taken now, which the capacity may not go below (shown as a hint; the server decides). */
  seatsTaken?: number
  /** Edit only: whether the workshop charges. It cannot change after creation, so it is told, not asked. */
  isPaid?: boolean
  /** Edit only: the form's reading of "the time was changed", shown as the heads-up of D156. */
  scheduleNotice?: ReactNode
}

function Section({ title, description, children }: { title: string; description?: string; children: ReactNode }) {
  return (
    <section className="grid gap-4 rounded-2xl border border-border bg-card p-5">
      <div>
        <h2 className="text-base leading-6 font-semibold">{title}</h2>
        {description && <p className="mt-0.5 text-sm text-muted-foreground">{description}</p>}
      </div>
      {children}
    </section>
  )
}

/** The inputs of the create and edit forms (S11, S12). They read the form from context: the pages own submit and errors. */
export function WorkshopFields({ mode, disabled, seatsTaken, isPaid, scheduleNotice }: Props) {
  const {
    register,
    watch,
    formState: { errors },
  } = useFormContext<WorkshopFormValues>()
  const paidChosen = watch('isPaid')
  const showPrice = mode === 'create' ? paidChosen : Boolean(isPaid)
  const star = mode === 'create' ? <span className="text-destructive"> *</span> : null

  const field = (name: keyof WorkshopFormValues, label: string, input: (props: Record<string, unknown>) => ReactNode, hint?: ReactNode) => {
    const error = errors[name]?.message
    const props = {
      id: `wf-${name}`,
      disabled,
      'aria-invalid': error ? true : undefined,
      'aria-describedby': error ? `wf-${name}-error` : hint ? `wf-${name}-hint` : undefined,
    }
    return (
      <Field>
        <Label htmlFor={`wf-${name}`}>
          {label}
          {star}
        </Label>
        {input(props)}
        {error ? (
          <FieldError id={`wf-${name}-error`} role="alert">
            {error}
          </FieldError>
        ) : (
          hint && <FieldHint id={`wf-${name}-hint`}>{hint}</FieldHint>
        )}
      </Field>
    )
  }

  return (
    <div className="grid gap-5">
      <Section title="Thông tin">
        {field('title', 'Tên workshop', (p) => <Input {...p} {...register('title')} />, 'Tối đa 255 ký tự.')}
        {field('description', 'Mô tả', (p) => <Textarea {...p} {...register('description')} />)}
        <div className="grid gap-4 md:grid-cols-2">
          {field('speakerName', 'Diễn giả', (p) => <Input {...p} {...register('speakerName')} />)}
          {field('location', 'Địa điểm', (p) => <Input {...p} {...register('location')} />)}
        </div>
        {field('speakerBio', 'Giới thiệu diễn giả', (p) => <Textarea {...p} className="min-h-16" {...register('speakerBio')} />)}
      </Section>

      <Section title="Thời gian" description="Giờ Việt Nam (UTC+7), không phụ thuộc múi giờ của máy bạn.">
        <div className="grid gap-4 md:grid-cols-2">
          {field('startTime', 'Bắt đầu', (p) => <Input type="datetime-local" {...p} {...register('startTime')} />)}
          {field('endTime', 'Kết thúc', (p) => <Input type="datetime-local" {...p} {...register('endTime')} />)}
        </div>
        {scheduleNotice}
      </Section>

      <Section title="Chỗ ngồi và phí">
        <div className="grid gap-4 md:grid-cols-2">
          {field(
            'capacity',
            'Sức chứa',
            (p) => <Input type="number" min={0} step={1} inputMode="numeric" className="tabular-nums" {...p} {...register('capacity')} />,
            mode === 'edit' && seatsTaken !== undefined
              ? `Không thấp hơn ${formatNumber(seatsTaken)}, số chỗ đã giữ.`
              : 'Số nguyên từ 0. Sau khi có người đăng ký, sức chứa không thể thấp hơn số chỗ đã giữ.',
          )}
          <Field>
            <span className="text-sm font-medium text-foreground">Phí</span>
            {mode === 'create' ? (
              <>
                <label className="flex min-h-10 items-center gap-2.5 text-sm">
                  <input type="checkbox" className="size-[18px] accent-primary" disabled={disabled} {...register('isPaid')} />
                  Workshop có thu phí
                </label>
                <FieldHint>Không đổi được sau khi tạo.</FieldHint>
              </>
            ) : (
              <>
                <p className="flex min-h-10 items-center text-sm">{isPaid ? 'Workshop có thu phí' : 'Workshop miễn phí'}</p>
                <FieldHint>Loại phí không đổi được sau khi tạo.</FieldHint>
              </>
            )}
          </Field>
        </div>
        {showPrice && (
          <div className="max-w-xs">
            {field('price', 'Giá (đ)', (p) => <Input inputMode="numeric" className="tabular-nums" {...p} {...register('price')} />)}
          </div>
        )}
      </Section>
    </div>
  )
}
