import { describe, expect, it } from 'vitest'

import { aWorkshop } from '@/testing/factories/workshop'
import {
  changesSchedule,
  EMPTY_WORKSHOP_FORM,
  parsePrice,
  toCreateRequest,
  toFormValues,
  toUpdateRequest,
  workshopFormSchema,
  type WorkshopFormValues,
} from '../workshop-form'

const valid: WorkshopFormValues = {
  title: ' Cloud Computing với AWS ',
  description: '',
  speakerName: 'Nguyễn Thị Thu Hà',
  speakerBio: '  ',
  location: 'Phòng B1.203',
  startTime: '2026-11-18T13:30',
  endTime: '2026-11-18T16:00',
  capacity: '40',
  isPaid: false,
  price: '',
}

const issues = (values: Partial<WorkshopFormValues>) => {
  const result = workshopFormSchema.safeParse({ ...valid, ...values })
  return result.success ? {} : Object.fromEntries(result.error.issues.map((issue) => [String(issue.path[0]), issue.message]))
}

describe('workshopFormSchema', () => {
  it('accepts a complete free workshop', () => {
    expect(workshopFormSchema.safeParse(valid).success).toBe(true)
  })

  it('names every required field that is empty', () => {
    expect(issues(EMPTY_WORKSHOP_FORM)).toMatchObject({
      title: 'Nhập tên workshop.',
      speakerName: 'Nhập tên diễn giả.',
      location: 'Nhập địa điểm.',
      startTime: 'Chọn giờ bắt đầu.',
      endTime: 'Chọn giờ kết thúc.',
      capacity: 'Nhập sức chứa.',
    })
  })

  it('refuses an end that is not after the start, at the end field (database-schema.md §3.1)', () => {
    expect(issues({ endTime: '2026-11-18T13:30' }).endTime).toBe('Giờ kết thúc phải sau giờ bắt đầu.')
    expect(issues({ endTime: '2026-11-18T12:00' }).endTime).toBe('Giờ kết thúc phải sau giờ bắt đầu.')
    expect(issues({ endTime: '2026-11-18T13:31' }).endTime).toBeUndefined()
  })

  it('needs a price above zero for a paid workshop, and none for a free one', () => {
    expect(issues({ isPaid: true, price: '' }).price).toBe('Nhập giá lớn hơn 0.')
    expect(issues({ isPaid: true, price: '0' }).price).toBe('Nhập giá lớn hơn 0.')
    expect(issues({ isPaid: true, price: 'abc' }).price).toBe('Nhập giá lớn hơn 0.')
    expect(issues({ isPaid: true, price: '150.000' }).price).toBeUndefined()
    expect(issues({ isPaid: false, price: 'abc' }).price).toBeUndefined() // ignored: a free workshop sends none
  })

  it('takes a capacity of zero or more, whole', () => {
    expect(issues({ capacity: '0' }).capacity).toBeUndefined()
    expect(issues({ capacity: '-1' }).capacity).toBe('Nhập một số nguyên từ 0.')
    expect(issues({ capacity: '2.5' }).capacity).toBe('Nhập một số nguyên từ 0.')
  })

  it('holds a text to 255 characters', () => {
    expect(issues({ title: 'a'.repeat(256) }).title).toBe('Tối đa 255 ký tự.')
    expect(issues({ title: 'a'.repeat(255) }).title).toBeUndefined()
  })
})

describe('parsePrice', () => {
  it('reads the ways a Vietnamese user writes a price', () => {
    expect(parsePrice('150000')).toBe(150000)
    expect(parsePrice('150.000')).toBe(150000)
    expect(parsePrice('150 000')).toBe(150000)
    expect(parsePrice('99,5')).toBe(99.5)
    expect(parsePrice('')).toBeNaN()
    expect(parsePrice('1e5')).toBeNaN()
  })
})

describe('toCreateRequest', () => {
  it('maps the form to the contract: Vietnam times as instants, blank optional text as null, no price when free', () => {
    expect(toCreateRequest(valid)).toEqual({
      title: 'Cloud Computing với AWS',
      description: null,
      speakerName: 'Nguyễn Thị Thu Hà',
      speakerBio: null,
      location: 'Phòng B1.203',
      startTime: '2026-11-18T06:30:00.000Z',
      endTime: '2026-11-18T09:00:00.000Z',
      capacity: 40,
      isPaid: false,
      price: null,
    })
  })

  it('sends the price of a paid workshop as a number, and drops a stale one when it is switched back to free', () => {
    expect(toCreateRequest({ ...valid, isPaid: true, price: '150.000' })).toMatchObject({ isPaid: true, price: 150000 })
    expect(toCreateRequest({ ...valid, isPaid: false, price: '150000' })).toMatchObject({ isPaid: false, price: null })
  })
})

describe('toUpdateRequest', () => {
  const workshop = aWorkshop({ isPaid: true, price: 150000, startTime: '2026-11-14T06:30:00Z', endTime: '2026-11-14T09:30:00Z' })

  it('round-trips a workshop into the form without changing a minute', () => {
    expect(toFormValues(workshop)).toMatchObject({ startTime: '2026-11-14T13:30', endTime: '2026-11-14T16:30', capacity: '50', price: '150000' })
  })

  it('sends only the fields that changed, and nothing for an untouched form (D155)', () => {
    const values = toFormValues(workshop)
    expect(toUpdateRequest(values, {}, workshop)).toEqual({})
    expect(toUpdateRequest({ ...values, title: ' Mới ' }, { title: true }, workshop)).toEqual({ title: 'Mới' })
    expect(toUpdateRequest({ ...values, capacity: '60' }, { capacity: true }, workshop)).toEqual({ capacity: 60 })
  })

  it('clears an optional text by sending null, not by omitting it', () => {
    const values = { ...toFormValues(workshop), description: '', speakerBio: ' ' }
    expect(toUpdateRequest(values, { description: true, speakerBio: true }, workshop)).toEqual({ description: null, speakerBio: null })
  })

  it('sends both times when either changed, so the new range is checked as a pair', () => {
    const values = { ...toFormValues(workshop), startTime: '2026-11-15T13:30' }
    expect(toUpdateRequest(values, { startTime: true }, workshop)).toEqual({
      startTime: '2026-11-15T06:30:00.000Z',
      endTime: '2026-11-14T09:30:00.000Z',
    })
    expect(changesSchedule({ startTime: true })).toBe(true)
    expect(changesSchedule({ title: true })).toBe(false)
  })

  it('never sends isPaid, and no price for a free workshop', () => {
    const free = aWorkshop({ isPaid: false, price: null })
    const values = { ...toFormValues(free), isPaid: true, price: '5' }
    expect(toUpdateRequest(values, { isPaid: true, price: true }, free)).toEqual({})
    expect(toUpdateRequest({ ...toFormValues(workshop), price: '200000' }, { price: true }, workshop)).toEqual({ price: 200000 })
  })
})
