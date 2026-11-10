import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

// Task 7.5: a student never downloads the administration pages. A static `import { … } from '@/routes/admin/…'` in the route
// table would pull them (and the form libraries only they use) back into the entry chunk, and nothing else would notice: every
// test would still pass. So the source is read, the same way no-role-names.test.ts reads the admin code.
const source = readFileSync(resolve(__dirname, '../routes.tsx'), 'utf8')

describe('the route table', () => {
  it('imports no administration page statically', () => {
    const staticImports = source.split('\n').filter((line) => /^import\b.*from\s+'@\/routes\/admin\//.test(line))
    expect(staticImports).toEqual([])
  })

  it('loads each administration page through lazy()', () => {
    const lazyPages = [...source.matchAll(/lazy\(\(\) => import\('@\/routes\/admin\/(\w+)'\)/g)].map((match) => match[1])
    expect(lazyPages.sort()).toEqual(['AdminIndexPage', 'WorkshopCreatePage', 'WorkshopEditPage', 'WorkshopListPage', 'WorkshopReportPage'])
  })
})
