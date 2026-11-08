# UniHub Web

The Web Application: the **student journey** (workshop catalog and detail with a live seat count, sign-in, registration, checkout, QR e-ticket, my registrations, notification inbox) and the Organizer/Admin **workshop report** page.

React 19, TypeScript, Vite, TanStack Query, Zustand, React Hook Form + Zod, Tailwind CSS and shadcn/ui. Standard: [`docs/guidelines/web-coding-standards.md`](../../docs/guidelines/web-coding-standards.md). The API types are generated from the contract in [`packages/api-contract`](../../packages/api-contract).

## Prerequisites

- Node.js 22 and npm
- The Backend API running on `http://localhost:8080` for anything beyond the tests: see [`apps/backend/README.md`](../backend/README.md)

## Setup and run

All commands run from this directory (`apps/web`).

```sh
npm ci
npm run dev
```

Open the URL Vite prints (`http://localhost:5173` by default). `npm run dev` first generates the API types from `packages/api-contract`, then starts the dev server, which proxies `/api` to the backend on `localhost:8080`.

Sign in with a seeded dev account such as `student1@dev.unihub.local` (the password is the one you set for `UNIHUB_DEV_SEED_STUDENT_PASSWORD` in the backend's `.env`). The checkout page offers Stripe **test** payment tokens in development. Confirmation emails the backend sends can be read in Mailpit at `http://127.0.0.1:8025`.

## Configuration

Read once at startup by `src/shared/config/env.ts`; a bad value fails fast.

| Variable | Default | Meaning |
|---|---|---|
| `VITE_API_BASE_URL` | empty (same origin) | Origin of the Backend API. In dev the Vite proxy forwards `/api` to `localhost:8080`. A production build also lists it in the CSP `connect-src`. |
| `VITE_PAYMENT_TEST_MODE` | `false` | Shows the Stripe **test-token** dropdown on checkout (`pm_card_visa`, `pm_card_chargeDeclined`). `.env.development` turns it on. A build without it has no way to pay, so to try checkout on `vite preview` build with `VITE_PAYMENT_TEST_MODE=true npm run build`. |

## Commands

| Command | What it does |
|---|---|
| `npm run dev` | Dev server (generates the API types first). |
| `npm test` | Vitest + React Testing Library; MSW mocks the Backend API, so no backend is needed. |
| `npm run typecheck` | `tsc -b`. |
| `npm run lint` | oxlint plus ESLint and the import-boundary rules (`app → routes → features → shared`). |
| `npm run build` | Type-check and production build. The build gets a Content-Security-Policy `<meta>` tag (`vite-plugins/csp.ts`); the dev server does not. |
| `npm run preview` | Serves the production build locally. |

## Build the image

```sh
docker build -f apps/web/Dockerfile -t unihub-web:dev .
docker build -f apps/web/Dockerfile --build-arg VITE_PAYMENT_TEST_MODE=true -t unihub-web:dev .   # with test tokens
```

Run these from the **repository root**: the build context is the root because the build generates its API types from `packages/api-contract`. The image serves the build with nginx on the same origin as the API, with the Content-Security-Policy as an HTTP header. CI builds it for `amd64` and `arm64`.
