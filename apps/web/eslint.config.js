// Boundary rules only (web-coding-standards.md §2) — oxlint keeps every other lint rule.
import boundaries from 'eslint-plugin-boundaries'
import tseslint from 'typescript-eslint'

const featureIndex = { element: { type: 'feature', fileInternalPath: 'index.ts' } }

export default [
  { ignores: ['dist/**', 'src/shared/api/schema.gen.ts'] },
  {
    files: ['src/**/*.{ts,tsx}'],
    languageOptions: { parser: tseslint.parser },
    plugins: { boundaries },
    settings: {
      // Resolves the '@/…' alias (tsconfig.app.json "paths") so imports map to elements.
      'import/resolver': { typescript: { project: './tsconfig.app.json' } },
      'boundaries/legacy-templates': false,
      'boundaries/elements': [
        { type: 'app', pattern: 'src/app' },
        { type: 'routes', pattern: 'src/routes' },
        { type: 'feature', pattern: 'src/features/*', capture: ['featureName'] },
        { type: 'shared', pattern: 'src/shared' },
        { type: 'testing', pattern: 'src/testing' },
      ],
      'boundaries/files': [{ category: 'test', pattern: 'src/**/*.test.{ts,tsx}' }],
    },
    rules: {
      'boundaries/dependencies': [
        'error',
        {
          default: 'disallow',
          policies: [
            // §2 rules 1–4: app → routes → features → shared, and no feature → other feature.
            // §2 rule 5: from outside a feature, only its index.ts is importable.
            {
              from: { element: { type: 'app' } },
              allow: { to: [{ element: { types: { anyOf: ['routes', 'shared'] } } }, featureIndex] },
            },
            {
              from: { element: { type: 'routes' } },
              allow: { to: [{ element: { type: 'shared' } }, featureIndex] },
            },
            {
              from: { element: { type: 'feature' } },
              allow: { to: { element: { type: 'shared' } } },
            },
            {
              from: { element: { type: 'feature' } },
              allow: {
                to: { element: { type: 'feature', captured: { featureName: '{{from.element.captured.featureName}}' } } },
              },
            },
            {
              from: { element: { type: 'shared' } },
              allow: { to: { element: { type: 'shared' } } },
            },
            // §2 rule 7: src/testing may import any layer; only test files may import src/testing.
            {
              from: { element: { type: 'testing' } },
              allow: { to: [{ element: { types: { anyOf: ['app', 'routes', 'shared', 'testing'] } } }, featureIndex] },
            },
            { from: { file: { categories: 'test' } }, allow: { to: { element: { type: 'testing' } } } },
            // §2 rule 6: only features/*/api/ calls the http client.
            {
              from: { file: { path: '!src/features/*/api/**' } },
              disallow: { to: { file: { path: 'src/shared/api/http-client.ts' } } },
              message: 'Only features/<name>/api/ may import the http client — web-coding-standards.md §2 rule 6.',
            },
          ],
        },
      ],
    },
  },
  // Zod is imported only through shared/config/zod.ts, which turns the eval probe off before any schema is built (the CSP
  // forbids the probe; see that file for why the import graph, not the order in main.tsx, has to carry this).
  {
    files: ['src/**/*.{ts,tsx}'],
    ignores: ['src/shared/config/zod.ts', 'src/**/__tests__/**', 'src/**/*.test.{ts,tsx}'],
    rules: {
      'no-restricted-imports': [
        'error',
        { paths: [{ name: 'zod', message: "Import `z` from '@/shared/config/zod' — it sets jitless before any schema is built (CSP)." }] },
      ],
    },
  },
  // web-coding-standards.md §12: a test lives in a __tests__/ folder. Vitest discovers tests by
  // name, so a misplaced one would still run — only this check keeps the convention from eroding.
  {
    files: ['src/**/*.test.{ts,tsx}'],
    ignores: ['src/**/__tests__/**'],
    rules: {
      'no-restricted-syntax': [
        'error',
        {
          selector: 'Program',
          message: 'Move this test into a __tests__/ folder next to the file it tests (web-coding-standards.md §12).',
        },
      ],
    },
  },
]
