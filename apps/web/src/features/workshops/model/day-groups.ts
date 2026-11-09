import { dayHeading, ymdOf } from '@/shared/lib/vn-time'

export type DayGroup<T> = { day: string; heading: string; items: T[] }

/**
 * Splits a list already sorted by start time into one group per calendar day in Vietnam (D140). Only this page's items
 * are grouped: a day that runs on into the next page starts a second group there with the same heading. Whether a
 * heading continues the previous page's is not knowable from one page, so it carries no "(tiếp)".
 */
export function groupByDay<T extends { startTime: string }>(items: T[]): DayGroup<T>[] {
  const groups: DayGroup<T>[] = []
  for (const item of items) {
    const day = ymdOf(item.startTime)
    const last = groups[groups.length - 1]
    if (last && last.day === day) last.items.push(item)
    else groups.push({ day, heading: dayHeading(day), items: [item] })
  }
  return groups
}
