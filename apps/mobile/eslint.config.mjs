// Boundary rules (docs/guidelines/mobile-coding-standards.md §2) and the few bans that protect
// secrets and the offline guarantee. oxlint keeps every other rule.
import boundaries from 'eslint-plugin-boundaries'
import tseslint from 'typescript-eslint'

const featureIndex = { element: { type: 'feature', fileInternalPath: 'index.ts' } }

export default [
  {
    ignores: [
      'node_modules/**',
      '.expo/**',
      'src/shared/api/schema.gen.ts',
      'babel.config.js',
      'jest.config.js',
      'eslint.config.mjs',
    ],
  },
  {
    files: ['app/**/*.{ts,tsx}', 'src/**/*.{ts,tsx}'],
    languageOptions: { parser: tseslint.parser },
    plugins: { boundaries },
    settings: {
      'import/resolver': { typescript: { project: './tsconfig.json' } },
      'boundaries/legacy-templates': false,
      'boundaries/elements': [
        { type: 'route', pattern: 'app/**', partialMatch: false },
        { type: 'app', pattern: 'src/app/**', partialMatch: false },
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
            // app/ (the Expo Router files) → src/app, features (their index only), shared.
            {
              from: { element: { type: 'route' } },
              allow: { to: [{ element: { types: { anyOf: ['app', 'shared'] } } }, featureIndex] },
            },
            {
              from: { element: { type: 'app' } },
              allow: { to: [{ element: { type: 'shared' } }, featureIndex] },
            },
            // A feature uses shared code and itself, never another feature.
            {
              from: { element: { type: 'feature' } },
              allow: { to: { element: { type: 'shared' } } },
            },
            {
              from: { element: { type: 'feature' } },
              allow: {
                to: {
                  element: {
                    type: 'feature',
                    captured: { featureName: '{{from.element.captured.featureName}}' },
                  },
                },
              },
            },
            {
              from: { element: { type: 'shared' } },
              allow: { to: { element: { type: 'shared' } } },
            },
            // src/testing may import any layer; only test files may import it.
            {
              from: { element: { type: 'testing' } },
              allow: {
                to: [{ element: { types: { anyOf: ['app', 'shared', 'testing'] } } }, featureIndex],
              },
            },
            {
              from: { file: { categories: 'test' } },
              allow: { to: { element: { type: 'testing' } } },
            },
          ],
        },
      ],
    },
  },
  {
    files: ['app/**/*.{ts,tsx}', 'src/**/*.{ts,tsx}'],
    ignores: ['**/*.test.{ts,tsx}', 'src/testing/**'],
    rules: {
      // A log line is where a token or a ticket payload ends up in a bug report (checkin.md §6).
      'no-console': 'error',
    },
  },
  {
    // Only the API client talks to the network. The offline guarantee rests on `verifyTicket` and
    // `processScan` never reaching it; a global `fetch` anywhere else is a way around that.
    files: ['app/**/*.{ts,tsx}', 'src/**/*.{ts,tsx}'],
    ignores: ['src/shared/api/**', '**/*.test.{ts,tsx}', 'src/testing/**'],
    rules: {
      'no-restricted-globals': [
        'error',
        { name: 'fetch', message: 'Use the API client (src/shared/api).' },
      ],
    },
  },
]
