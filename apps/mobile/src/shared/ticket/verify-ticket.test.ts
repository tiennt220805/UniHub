import { createHmac, randomUUID } from 'node:crypto'

import { asVerificationKey, issueTicket, newTestKey, randomTicketCode } from '@/testing/tickets'

import { verifyTicket } from './verify-ticket'

const key = newTestKey()
const verificationKey = asVerificationKey(key)
const b64 = (value: string | object) =>
  Buffer.from(typeof value === 'string' ? value : JSON.stringify(value)).toString('base64url')

describe('verifyTicket: a genuine ticket is accepted (M-43)', () => {
  it('accepts a ticket and returns only what the signed payload says', () => {
    const workshopId = randomUUID()
    const ticketCode = randomTicketCode()
    const token = issueTicket(key, { ticketCode, workshopId, iat: 1_793_500_123 })

    expect(verifyTicket(token, verificationKey)).toEqual({
      ok: true,
      ticket: { ticketCode, workshopId, issuedAt: 1_793_500_123 },
    })
  })

  it('accepts every one of 300 genuine tickets, high-s signatures included', () => {
    // ECDSA signatures are random: about half have s above half the curve order. A verifier that
    // insists on low-s (the default of @noble/curves) would reject about 150 of these.
    let accepted = 0
    for (let i = 0; i < 300; i++) {
      const token = issueTicket(key)
      if (verifyTicket(token, verificationKey).ok) accepted++
    }
    expect(accepted).toBe(300)
  })

  it('is the same ticket whatever the case of the workshop id', () => {
    const workshopId = randomUUID()
    const token = issueTicket(key, { workshopId: workshopId.toUpperCase() })

    const result = verifyTicket(token, verificationKey)

    expect(result.ok && result.ticket.workshopId).toBe(workshopId)
  })
})

describe('verifyTicket: S-10 vectors, each rejected offline', () => {
  it('V1 payload edited, signature kept', () => {
    const genuine = issueTicket(key, { ticketCode: randomTicketCode() })
    const [header, , signature] = genuine.split('.') as [string, string, string]
    const edited = `${header}.${b64({ ticket_code: 'FORGED', workshop_id: randomUUID(), iat: 1 })}.${signature}`

    expect(verifyTicket(edited, verificationKey)).toEqual({ ok: false, reason: 'SIGNATURE' })
  })

  it('V2 signature stripped or truncated', () => {
    const genuine = issueTicket(key)
    const [header, payload, signature] = genuine.split('.') as [string, string, string]

    for (const forged of [
      `${header}.${payload}.`,
      `${header}.${payload}`,
      `${header}.${payload}.${signature.slice(0, 40)}`,
      `${header}.${payload}.${signature}${signature}`,
    ]) {
      expect(verifyTicket(forged, verificationKey).ok).toBe(false)
    }
  })

  it('V3 valid signature from an attacker’s own ES256 key', () => {
    const attacker = newTestKey()

    const forged = issueTicket(key, {}, { signWith: attacker })

    expect(verifyTicket(forged, verificationKey)).toEqual({ ok: false, reason: 'SIGNATURE' })
  })

  it('V4 alg none', () => {
    const forged = `${b64({ alg: 'none' })}.${b64({ ticket_code: 'FORGED', workshop_id: randomUUID(), iat: 1 })}.`

    expect(verifyTicket(forged, verificationKey).ok).toBe(false)
    expect(verifyTicket(`${forged}AAAA`, verificationKey)).toEqual({
      ok: false,
      reason: 'ALGORITHM',
    })
  })

  it('V5 header claiming HS256, MACed with the public key material as the secret', () => {
    const header = b64({ alg: 'HS256' })
    const payload = b64({ ticket_code: 'FORGED', workshop_id: randomUUID(), iat: 1 })
    const secret = Buffer.concat([
      Buffer.from([0x04]),
      Buffer.from(key.publicJwk.x, 'base64url'),
      Buffer.from(key.publicJwk.y, 'base64url'),
    ])
    const mac = createHmac('sha256', secret).update(`${header}.${payload}`).digest('base64url')

    expect(verifyTicket(`${header}.${payload}.${mac}`, verificationKey)).toEqual({
      ok: false,
      reason: 'ALGORITHM',
    })
  })

  it('V6 malformed or non-JWS input', () => {
    for (const input of [
      '',
      'not a ticket',
      'a.b',
      'a.b.c.d',
      '..',
      'eyJhbGciOiJFUzI1NiJ9..',
      `${b64('not json')}.${b64('{}')}.${b64('x')}`,
      `${b64('[]')}.${b64('{}')}.${b64('x')}`,
      '€.€.€',
      'ab=.cd=.ef=',
    ]) {
      expect(verifyTicket(input, verificationKey).ok).toBe(false)
    }
  })

  it('V7 a ticket signed under the previous key, after the single cached key was replaced', () => {
    const oldKey = newTestKey()
    const ticket = issueTicket(oldKey)
    expect(verifyTicket(ticket, asVerificationKey(oldKey)).ok).toBe(true)

    const refreshed = asVerificationKey(newTestKey())

    expect(verifyTicket(ticket, refreshed)).toEqual({ ok: false, reason: 'SIGNATURE' })
  })
})

describe('verifyTicket: the ticket does not choose how it is checked', () => {
  it('ignores a jwk, kid or jku in the header and still checks against the cached key', () => {
    const attacker = newTestKey()
    const header = {
      alg: 'ES256',
      jwk: attacker.publicJwk,
      kid: 'attacker',
      jku: 'https://evil.test/k',
    }

    const forged = issueTicket(key, {}, { header, signWith: attacker })

    expect(verifyTicket(forged, verificationKey)).toEqual({ ok: false, reason: 'SIGNATURE' })
  })

  it('rejects a header with a crit member', () => {
    const token = issueTicket(key, {}, { header: { alg: 'ES256', crit: ['exp'], exp: 1 } })

    expect(verifyTicket(token, verificationKey)).toEqual({ ok: false, reason: 'ALGORITHM' })
  })

  it.each([
    ['ES384', 'ALGORITHM'],
    ['RS256', 'ALGORITHM'],
    ['es256', 'ALGORITHM'],
    ['', 'ALGORITHM'],
  ])('rejects alg %p', (alg, reason) => {
    expect(verifyTicket(issueTicket(key, {}, { header: { alg } }), verificationKey)).toEqual({
      ok: false,
      reason,
    })
  })

  it('rejects a genuinely signed ticket whose payload is not the issued shape', () => {
    const base = { ticket_code: randomTicketCode(), workshop_id: randomUUID(), iat: 1 }
    for (const payload of [
      { ...base, ticket_code: '' },
      { ...base, ticket_code: 'x'.repeat(65) },
      { ...base, ticket_code: 'has space' },
      { ...base, ticket_code: 12345 },
      { ...base, workshop_id: 'not-a-uuid' },
      { ...base, iat: '1' },
      { ...base, iat: 1.5 },
      { ticket_code: base.ticket_code, workshop_id: base.workshop_id },
    ]) {
      const token = issueTicket(key, {}, { payload })
      expect(verifyTicket(token, verificationKey)).toEqual({ ok: false, reason: 'CLAIMS' })
    }
  })
})

describe('verifyTicket: any input ends in a result, never a crash', () => {
  it('rejects non-string input of every type', () => {
    for (const input of [
      undefined,
      null,
      0,
      42,
      true,
      {},
      [],
      ['a', 'b', 'c'],
      () => 'x',
      Symbol('s'),
    ]) {
      expect(verifyTicket(input, verificationKey)).toEqual({ ok: false, reason: 'MALFORMED' })
    }
  })

  it('rejects an oversized input without decoding it', () => {
    expect(verifyTicket('A'.repeat(100_000), verificationKey)).toEqual({
      ok: false,
      reason: 'MALFORMED',
    })
    expect(
      verifyTicket(`${'A'.repeat(1_024)}.${'A'.repeat(10)}.${'A'.repeat(86)}`, verificationKey).ok,
    ).toBe(false)
  })

  it('refuses everything when there is no key, and a key that is not a P-256 point', () => {
    const token = issueTicket(key)

    expect(verifyTicket(token, null)).toEqual({ ok: false, reason: 'NO_KEY' })
    expect(verifyTicket(token, { x: 'AAAA', y: 'AAAA' })).toEqual({ ok: false, reason: 'NO_KEY' })
    expect(verifyTicket(token, { x: '!!!', y: '???' })).toEqual({ ok: false, reason: 'NO_KEY' })
    const offCurve = {
      x: Buffer.alloc(32, 1).toString('base64url'),
      y: Buffer.alloc(32, 2).toString('base64url'),
    }
    expect(verifyTicket(token, offCurve).ok).toBe(false)
  })
})

describe('verifyTicket: no network', () => {
  it('never calls fetch, XMLHttpRequest or a socket', () => {
    const fetchSpy = jest.fn(() => {
      throw new Error('verifyTicket must not use the network')
    })
    const original = globalThis.fetch
    globalThis.fetch = fetchSpy as unknown as typeof fetch
    try {
      verifyTicket(issueTicket(key), verificationKey)
      verifyTicket('garbage', verificationKey)
      verifyTicket(issueTicket(key, {}, { signWith: newTestKey() }), verificationKey)
    } finally {
      globalThis.fetch = original
    }

    expect(fetchSpy).not.toHaveBeenCalled()
  })
})
