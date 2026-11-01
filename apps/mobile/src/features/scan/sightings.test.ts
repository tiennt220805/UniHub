import { createSightings } from './sightings'

const COOLDOWN = 2_500

describe('one physical scan is one record', () => {
  it('counts a code the first time it is seen', () => {
    expect(createSightings(COOLDOWN)('A', 0)).toBe(true)
  })

  it('ignores the camera reporting the same code again within the cooldown', () => {
    const isNew = createSightings(COOLDOWN)
    expect(isNew('A', 0)).toBe(true)

    expect([100, 400, 1_000, 2_400].map((t) => isNew('A', t))).toEqual([false, false, false, false])
  })

  it('a code that stays in view is one scan however long it stays', () => {
    const isNew = createSightings(COOLDOWN)

    const counted = Array.from({ length: 60 }, (_, i) => isNew('A', i * 500)).filter(Boolean)

    expect(counted).toHaveLength(1)
  })

  it('counts a code again only after the camera stopped reporting it for the cooldown', () => {
    const isNew = createSightings(COOLDOWN)
    expect(isNew('A', 0)).toBe(true)
    expect(isNew('A', 1_000)).toBe(false)

    expect(isNew('A', 1_000 + COOLDOWN - 1)).toBe(false) // renewed at 1 000, still the same scan
    expect(isNew('A', 1_000 + COOLDOWN - 1 + COOLDOWN)).toBe(true) // absent for a full cooldown
  })

  it('exactly at the cooldown counts again (boundary)', () => {
    const isNew = createSightings(COOLDOWN)
    isNew('A', 0)

    expect(isNew('A', COOLDOWN - 1)).toBe(false)
    const second = createSightings(COOLDOWN)
    second('A', 0)
    expect(second('A', COOLDOWN)).toBe(true)
  })

  it('two codes in frame, reported alternately, are two scans and not a record per report', () => {
    const isNew = createSightings(COOLDOWN)

    const results = Array.from({ length: 200 }, (_, i) =>
      isNew(i % 2 === 0 ? 'A-1' : 'A-2', i * 50),
    )

    expect(results.filter(Boolean)).toHaveLength(2)
  })

  it('several codes in frame at once are each counted once', () => {
    const isNew = createSightings(COOLDOWN)
    const codes = ['A-1', 'A-2', 'GIẢ', 'LẠ']

    const counted = Array.from({ length: 100 }, (_, i) => isNew(codes[i % 4]!, i * 40)).filter(
      Boolean,
    )

    expect(counted).toHaveLength(4)
  })

  it('forgets codes it has not seen for the cooldown, so it does not grow without bound', () => {
    const isNew = createSightings(COOLDOWN)
    for (let i = 0; i < 1_000; i++) isNew(`code-${i}`, i * 3_000)

    expect(isNew('code-0', 1_000 * 3_000)).toBe(true)
  })
})
