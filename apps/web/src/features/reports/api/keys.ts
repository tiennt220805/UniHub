export const reportKeys = {
  all: ['reports'] as const,
  workshop: (workshopId: string) => [...reportKeys.all, 'workshop', workshopId] as const,
}
