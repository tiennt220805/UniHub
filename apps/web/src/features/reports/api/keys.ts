export const reportKeys = {
  all: ['reports'] as const,
  workshop: (workshopId: string) => [...reportKeys.all, 'workshop', workshopId] as const,
  attendances: (workshopId: string) => [...reportKeys.all, 'attendances', workshopId] as const,
  attendancePage: (workshopId: string, page: number) => [...reportKeys.attendances(workshopId), page] as const,
}
