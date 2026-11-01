import { createPrivateKey, generateKeyPairSync, randomBytes, randomUUID, sign } from 'node:crypto'

// Tickets signed at test time from a throwaway key: no ticket, JWT-shaped string or private key is
// ever committed (the secret scan would stop it, rightly). The shape follows what the Backend API
// issues (`checkin.md` §3, §5): header {"alg":"ES256"}, payload {ticket_code, workshop_id, iat}, a raw
// 64-byte (IEEE P1363) signature — the format Nimbus produces.

const CROCKFORD = '0123456789ABCDEFGHJKMNPQRSTVWXYZ'

export type TestKey = {
  readonly privateKey: ReturnType<typeof createPrivateKey>
  readonly publicJwk: { kty: 'EC'; crv: 'P-256'; x: string; y: string }
}

export function newTestKey(): TestKey {
  const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' })
  const jwk = publicKey.export({ format: 'jwk' })
  return {
    privateKey,
    publicJwk: { kty: 'EC', crv: 'P-256', x: jwk.x as string, y: jwk.y as string },
  }
}

const b64 = (value: string | Uint8Array) => Buffer.from(value).toString('base64url')

export function randomTicketCode(): string {
  return Array.from(randomBytes(26), (byte) => CROCKFORD[byte % 32]).join('')
}

type TicketParts = {
  header?: unknown
  payload?: unknown
  /** Overrides the signature segment as is. */
  signature?: string
  /** Signs with this key instead of the key the ticket will be checked against. */
  signWith?: TestKey
}

export function issueTicket(
  key: TestKey,
  claims: { ticketCode?: string; workshopId?: string; iat?: number } = {},
  parts: TicketParts = {},
): string {
  const header = b64(JSON.stringify(parts.header ?? { alg: 'ES256' }))
  const payload = b64(
    JSON.stringify(
      parts.payload ?? {
        ticket_code: claims.ticketCode ?? randomTicketCode(),
        workshop_id: claims.workshopId ?? randomUUID(),
        iat: claims.iat ?? 1_793_500_000,
      },
    ),
  )
  const signingKey = (parts.signWith ?? key).privateKey
  const signature =
    parts.signature ??
    b64(
      sign('sha256', Buffer.from(`${header}.${payload}`), {
        key: signingKey,
        dsaEncoding: 'ieee-p1363',
      }),
    )
  return `${header}.${payload}.${signature}`
}

export const asVerificationKey = (key: TestKey) => ({ x: key.publicJwk.x, y: key.publicJwk.y })
