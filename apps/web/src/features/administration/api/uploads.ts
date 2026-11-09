import { request } from '@/shared/api/http-client'
import type { components } from '@/shared/api/schema.gen'

export type CoverImageUploadUrl = components['schemas']['CoverImageUploadUrlResponse']
export type CoverImageUploadConfirm = components['schemas']['CoverImageUploadConfirmResponse']
export type BrochureUploadUrl = components['schemas']['BrochureUploadUrlResponse']
export type BrochureUploadConfirm = components['schemas']['BrochureUploadConfirmResponse']

const base = (workshopId: string) => `/api/v1/workshops/${encodeURIComponent(workshopId)}`
const post = (body?: unknown): RequestInit =>
  body === undefined
    ? { method: 'POST' }
    : { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) }

// The two-step upload (D162): ask for a URL, send the bytes to it (shared/api/put-with-progress.ts), confirm. The bytes never
// pass through the Backend API.
export const requestCoverImageUploadUrl = (workshopId: string, file: { contentType: string; contentLength: number }) =>
  request<CoverImageUploadUrl>(`${base(workshopId)}/cover-image-upload-url`, post(file))

export const confirmCoverImageUpload = (workshopId: string) =>
  request<CoverImageUploadConfirm>(`${base(workshopId)}/cover-image-upload-confirm`, post())

export const requestBrochureUploadUrl = (workshopId: string) =>
  request<BrochureUploadUrl>(`${base(workshopId)}/brochure-upload-url`, post({ contentType: 'application/pdf' }))

export const confirmBrochureUpload = (workshopId: string) =>
  request<BrochureUploadConfirm>(`${base(workshopId)}/brochure-upload-confirm`, post())
