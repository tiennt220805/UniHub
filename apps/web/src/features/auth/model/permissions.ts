// Codes copied from `x-required-permission` in packages/api-contract/paths/*.yaml (openapi-typescript
// does not emit that extension). Only the codes the Web Application uses; no other code is added
// until the contract adds it (web-coding-standards.md §11).
export const Permission = {
  REGISTRATION_CREATE: 'REGISTRATION_CREATE',
  WORKSHOP_CREATE: 'WORKSHOP_CREATE',
  WORKSHOP_EDIT: 'WORKSHOP_EDIT',
  REPORT_VIEW: 'REPORT_VIEW',
  STUDENT_SYNC_VIEW: 'STUDENT_SYNC_VIEW',
  STUDENT_SYNC_RESOLVE: 'STUDENT_SYNC_RESOLVE',
} as const
export type Permission = (typeof Permission)[keyof typeof Permission]
