// What the browser checks before it asks for an upload URL (D162). It is for the user's sake: the Backend and the storage decide
// (the URL is signed for the type and the exact size, the server reads the stored bytes). The type is chosen once here and used
// for both the URL request and the PUT's header: `file.type` can be empty or odd, and a mismatch is a failed signature.

export const COVER_MAX_BYTES = 2_097_152 // 2 MiB, the contract's maximum for `contentLength`
export const COVER_TYPES = ['image/jpeg', 'image/png', 'image/webp'] as const
export type CoverType = (typeof COVER_TYPES)[number]

const COVER_BY_EXTENSION: Record<string, CoverType> = { jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', webp: 'image/webp' }

const extensionOf = (name: string) => name.slice(name.lastIndexOf('.') + 1).toLowerCase()

export type CoverCheck = { ok: true; contentType: CoverType } | { ok: false; message: string }

export const megabytes = (bytes: number) => `${(bytes / 1_048_576).toFixed(1).replace('.', ',')} MB`

export function checkCoverFile(file: Pick<File, 'name' | 'type' | 'size'>): CoverCheck {
  const declared = COVER_TYPES.find((type) => type === file.type)
  const contentType = declared ?? (file.type === '' ? COVER_BY_EXTENSION[extensionOf(file.name)] : undefined)
  if (!contentType) return { ok: false, message: 'Chỉ nhận ảnh JPEG, PNG hoặc WebP. SVG và GIF không được dùng.' }
  if (file.size === 0) return { ok: false, message: 'File này trống. Chọn một ảnh khác.' }
  if (file.size > COVER_MAX_BYTES) {
    return { ok: false, message: `Ảnh nặng ${megabytes(file.size)}, lớn hơn mức ${megabytes(COVER_MAX_BYTES)}. Chọn ảnh nhỏ hơn.` }
  }
  return { ok: true, contentType }
}

export const BROCHURE_TYPE = 'application/pdf'

export type BrochureCheck = { ok: true } | { ok: false; message: string }

/** A PDF by its type, or by its name when the browser gives no type. The contract sets no size limit, so neither does this. */
export function checkBrochureFile(file: Pick<File, 'name' | 'type' | 'size'>): BrochureCheck {
  const isPdf = file.type === BROCHURE_TYPE || (file.type === '' && extensionOf(file.name) === 'pdf')
  if (!isPdf) return { ok: false, message: 'Chỉ nhận file PDF.' }
  if (file.size === 0) return { ok: false, message: 'File này trống. Chọn một file khác.' }
  return { ok: true }
}
