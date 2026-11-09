import { z } from 'zod'

import { fromDateTimeLocal, toDateTimeLocal } from '@/shared/lib/vn-time'
import type { CreateWorkshopRequest, UpdateWorkshopRequest, Workshop } from '../api/requests'

// The form's values are what the inputs hold: strings, and a checkbox. The mapping to the contract's request types is
// explicit (web-coding-standards.md §9, Rules #10), so a change to the contract is a compile error here, not a silent
// drift. The times are `datetime-local` values: wall-clock time in Vietnam (D161).

const required = (message: string) => z.string().trim().min(1, message)

export const workshopFormSchema = z
  .object({
    title: required('Nhập tên workshop.').max(255, 'Tối đa 255 ký tự.'),
    description: z.string(),
    speakerName: required('Nhập tên diễn giả.').max(255, 'Tối đa 255 ký tự.'),
    speakerBio: z.string(),
    location: required('Nhập địa điểm.').max(255, 'Tối đa 255 ký tự.'),
    startTime: z.string().min(1, 'Chọn giờ bắt đầu.'),
    endTime: z.string().min(1, 'Chọn giờ kết thúc.'),
    capacity: z.string().trim().min(1, 'Nhập sức chứa.'),
    isPaid: z.boolean(),
    price: z.string(),
  })
  .superRefine((values, ctx) => {
    const capacity = Number(values.capacity)
    if (values.capacity.trim() !== '' && (!Number.isInteger(capacity) || capacity < 0)) {
      ctx.addIssue({ code: 'custom', path: ['capacity'], message: 'Nhập một số nguyên từ 0.' })
    }
    const start = fromDateTimeLocal(values.startTime)
    const end = fromDateTimeLocal(values.endTime)
    if (values.startTime !== '' && start === null) ctx.addIssue({ code: 'custom', path: ['startTime'], message: 'Giờ bắt đầu không hợp lệ.' })
    if (values.endTime !== '' && end === null) ctx.addIssue({ code: 'custom', path: ['endTime'], message: 'Giờ kết thúc không hợp lệ.' })
    if (start !== null && end !== null && new Date(end) <= new Date(start)) {
      ctx.addIssue({ code: 'custom', path: ['endTime'], message: 'Giờ kết thúc phải sau giờ bắt đầu.' })
    }
    // Paid needs a price above zero; free carries none (the contract's if/then/else, database-schema.md §3.1).
    if (values.isPaid && !(parsePrice(values.price) > 0)) {
      ctx.addIssue({ code: 'custom', path: ['price'], message: 'Nhập giá lớn hơn 0.' })
    }
  })

export type WorkshopFormValues = z.infer<typeof workshopFormSchema>

export const EMPTY_WORKSHOP_FORM: WorkshopFormValues = {
  title: '',
  description: '',
  speakerName: '',
  speakerBio: '',
  location: '',
  startTime: '',
  endTime: '',
  capacity: '',
  isPaid: false,
  price: '',
}

/** "150000", "150.000" and "150 000" are all 150000; anything else is NaN. */
export function parsePrice(text: string): number {
  const digits = text.trim().replace(/[.\s]/g, '')
  return /^\d+(,\d+)?$/.test(digits) ? Number(digits.replace(',', '.')) : Number.NaN
}

/** A blank optional text is `null` ("none"), which is how the contract clears one. */
const optionalText = (value: string) => (value.trim() === '' ? null : value.trim())

export function toFormValues(workshop: Workshop): WorkshopFormValues {
  return {
    title: workshop.title,
    description: workshop.description ?? '',
    speakerName: workshop.speakerName,
    speakerBio: workshop.speakerBio ?? '',
    location: workshop.location,
    startTime: toDateTimeLocal(workshop.startTime),
    endTime: toDateTimeLocal(workshop.endTime),
    capacity: String(workshop.capacity),
    isPaid: workshop.isPaid,
    price: workshop.price == null ? '' : String(workshop.price),
  }
}

// Only called with values that passed the schema, so the instants and numbers are known good; the `?? ''` keeps the type honest.
export function toCreateRequest(values: WorkshopFormValues): CreateWorkshopRequest {
  return {
    title: values.title.trim(),
    description: optionalText(values.description),
    speakerName: values.speakerName.trim(),
    speakerBio: optionalText(values.speakerBio),
    location: values.location.trim(),
    startTime: fromDateTimeLocal(values.startTime) ?? '',
    endTime: fromDateTimeLocal(values.endTime) ?? '',
    capacity: Number(values.capacity),
    isPaid: values.isPaid,
    price: values.isPaid ? parsePrice(values.price) : null,
  }
}

export type DirtyFields = Partial<Record<keyof WorkshopFormValues, boolean | undefined>>

/**
 * The PATCH of an edit (D155): only the fields the user changed. `isPaid` is never sent (it cannot change after creation) and
 * neither is `price` for a free workshop. Changing either time sends both, so the new range is checked as a pair. An empty
 * result means nothing changed.
 */
export function toUpdateRequest(values: WorkshopFormValues, dirty: DirtyFields, workshop: Pick<Workshop, 'isPaid'>): UpdateWorkshopRequest {
  const body: UpdateWorkshopRequest = {}
  if (dirty.title) body.title = values.title.trim()
  if (dirty.description) body.description = optionalText(values.description)
  if (dirty.speakerName) body.speakerName = values.speakerName.trim()
  if (dirty.speakerBio) body.speakerBio = optionalText(values.speakerBio)
  if (dirty.location) body.location = values.location.trim()
  if (dirty.startTime || dirty.endTime) {
    body.startTime = fromDateTimeLocal(values.startTime) ?? ''
    body.endTime = fromDateTimeLocal(values.endTime) ?? ''
  }
  if (dirty.capacity) body.capacity = Number(values.capacity)
  if (dirty.price && workshop.isPaid) body.price = parsePrice(values.price)
  return body
}

/** Whether saving moves the workshop's time: the one change that tells the students (D156). */
export const changesSchedule = (dirty: DirtyFields) => Boolean(dirty.startTime || dirty.endTime)
