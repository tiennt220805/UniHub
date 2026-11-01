import { ApiError, type Api, type ScanEvent } from '@/shared/api/api-client'

type FakeApiOptions = {
  /** Called for every sync request; return the results to answer, or throw an ApiError. */
  sync?: (
    scans: ScanEvent[],
  ) => Promise<{ id: string; outcome: string }[]> | { id: string; outcome: string }[]
}

/** An Api whose sync answers every id with ACCEPTED unless told otherwise; records each batch it was sent. */
export function fakeApi(options: FakeApiOptions = {}) {
  const batches: ScanEvent[][] = []
  const api = {
    syncScans: async (scans: ScanEvent[]) => {
      batches.push(scans)
      const results = options.sync
        ? await options.sync(scans)
        : scans.map((scan) => ({ id: scan.id, outcome: 'ACCEPTED' }))
      return { results } as never
    },
  } as unknown as Api
  return { api, batches }
}

export const offline = () => new ApiError(null, 'offline')
export const httpError = (status: number) => new ApiError(status, `HTTP ${status}`)

type LoginPayload = {
  accessToken: string
  expiresIn: number
  user: { id: string; fullName: string; email: string; roles: string[] }
  permissions: string[]
}

export const staffLogin = (overrides: Partial<LoginPayload> = {}): LoginPayload => ({
  accessToken: 'opaque-token',
  expiresIn: 1800,
  user: {
    id: '11111111-2222-4333-8444-555555555555',
    fullName: 'Dev Staff',
    email: 'staff@dev.test',
    roles: ['Check-in Staff'],
  },
  permissions: ['CHECKIN_SCAN'],
  ...overrides,
})

/** Auth endpoints only: login and the verification key, each either a value or an error to throw. */
export function fakeAuthApi(options: {
  login?: LoginPayload | ApiError
  key?: { kty: string; crv: string; x: string; y: string } | ApiError
}) {
  const tokensSeen: (string | null)[] = []
  const apiFor = (token: string | null) => {
    tokensSeen.push(token)
    return {
      login: async () => {
        if (options.login instanceof ApiError) throw options.login
        return options.login ?? staffLogin()
      },
      verificationKey: async () => {
        if (options.key instanceof ApiError) throw options.key
        if (!options.key) throw new ApiError(null, 'no key configured')
        return options.key
      },
    } as unknown as Api
  }
  return { apiFor, tokensSeen }
}
