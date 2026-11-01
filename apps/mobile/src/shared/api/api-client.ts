import type { components } from './schema.gen'

export type LoginResponse = components['schemas']['LoginResponse']
export type VerificationKeyResponse = components['schemas']['VerificationKeyResponse']
export type ScanEvent = components['schemas']['ScanEvent']
export type SyncScanBatchResponse = components['schemas']['SyncScanBatchResponse']
export type WorkshopListResponse = components['schemas']['WorkshopListResponse']

/** `status` is the HTTP status, or null when no response arrived (offline, refused, timed out). */
export class ApiError extends Error {
  constructor(
    readonly status: number | null,
    message: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

const REQUEST_TIMEOUT_MS = 15_000

type Options = { baseUrl: string; token: () => string | null; fetchImpl?: typeof fetch }

/**
 * The three endpoints the Check-in Client uses, plus the workshop list for the room picker, typed
 * from the contract (`packages/api-contract`) with a thin `fetch` — the same approach as the Web
 * application. Never logs a body, a header or a token.
 */
export function createApi({ baseUrl, token, fetchImpl }: Options) {
  async function request<T>(path: string, init: RequestInit & { auth?: boolean } = {}): Promise<T> {
    const doFetch = fetchImpl ?? fetch
    const headers: Record<string, string> = { Accept: 'application/json' }
    if (init.body !== undefined) headers['Content-Type'] = 'application/json'
    const bearer = init.auth === false ? null : token()
    if (bearer) headers.Authorization = `Bearer ${bearer}`

    const controller = new AbortController()
    const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS)
    let response: Response
    try {
      response = await doFetch(`${baseUrl}${path}`, {
        method: init.method,
        body: init.body,
        headers,
        signal: controller.signal,
      })
    } catch {
      throw new ApiError(null, 'No response from the Backend API')
    } finally {
      clearTimeout(timer)
    }
    if (!response.ok) throw new ApiError(response.status, `Backend API answered ${response.status}`)
    try {
      return (await response.json()) as T
    } catch {
      throw new ApiError(response.status, 'Unreadable response from the Backend API')
    }
  }

  return {
    login: (email: string, password: string) =>
      request<LoginResponse>('/api/v1/auth/login', {
        method: 'POST',
        body: JSON.stringify({ email, password }),
        auth: false,
      }),
    verificationKey: () => request<VerificationKeyResponse>('/api/v1/checkin/verification-key'),
    syncScans: (scans: ScanEvent[]) =>
      request<SyncScanBatchResponse>('/api/v1/checkin/sync', {
        method: 'POST',
        body: JSON.stringify({ scans }),
      }),
    listWorkshops: (page: number) =>
      request<WorkshopListResponse>(`/api/v1/workshops?page=${page}&size=100`, { auth: false }),
  }
}

export type Api = ReturnType<typeof createApi>
