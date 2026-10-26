# UniHub web

The Web Application: the student journey (catalog, sign-in, registration, checkout, my registrations)
and, later, Workshop Administration. Standards: `docs/guidelines/web-coding-standards.md`.

## Configuration

Read once at startup by `src/shared/config/env.ts`; a bad value fails fast.

| Variable | Default | Meaning |
|---|---|---|
| `VITE_API_BASE_URL` | empty (same origin) | Origin of the Backend API. In dev the Vite proxy forwards `/api` to `localhost:8080`. A production build also lists it in the CSP `connect-src`. |
| `VITE_PAYMENT_TEST_MODE` | `false` | Shows the Stripe **test-token** dropdown on checkout (`pm_card_visa`, `pm_card_chargeDeclined`). `.env.development` turns it on. A build without it has no way to pay, so to try checkout on `vite preview` build with `VITE_PAYMENT_TEST_MODE=true npm run build`. |

## Commands

- `npm run dev` — dev server (types are generated from `packages/api-contract` first).
- `npm test` — Vitest + React Testing Library; MSW mocks the Backend API.
- `npm run lint` — oxlint plus the import-boundary rules.
- `npm run build` — type-check and production build; the build gets a Content-Security-Policy `<meta>` tag
  (`vite-plugins/csp.ts`), the dev server does not.

---

# React + TypeScript + Vite

This template provides a minimal setup to get React working in Vite with HMR and some Oxlint rules.

Currently, two official plugins are available:

- [@vitejs/plugin-react](https://github.com/vitejs/vite-plugin-react/blob/main/packages/plugin-react) uses [Oxc](https://oxc.rs)
- [@vitejs/plugin-react-swc](https://github.com/vitejs/vite-plugin-react/blob/main/packages/plugin-react-swc) uses [SWC](https://swc.rs/)

## React Compiler

The React Compiler is not enabled on this template because of its impact on dev & build performances. To add it, see [this documentation](https://react.dev/learn/react-compiler/installation).

## Expanding the Oxlint configuration

If you are developing a production application, we recommend enabling type-aware lint rules by installing `oxlint-tsgolint` and editing `.oxlintrc.json`:

```json
{
  "$schema": "./node_modules/oxlint/configuration_schema.json",
  "plugins": ["react", "typescript", "oxc"],
  "options": {
    "typeAware": true
  },
  "rules": {
    "react/rules-of-hooks": "error",
    "react/only-export-components": ["warn", { "allowConstantExport": true }]
  }
}
```

See the [Oxlint rules documentation](https://oxc.rs/docs/guide/usage/linter/rules) for the full list of rules and categories.
