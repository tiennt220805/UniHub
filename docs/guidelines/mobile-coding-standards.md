# Mobile Coding Standards — `apps/mobile` (Check-in Client)

> **Status:** mandatory for every line under `apps/mobile`, as `web-coding-standards.md` is for `apps/web`. Written with Phase 5 checklist task 8.1 (decision D57). Read "Rules at a Glance", then only the section for the code you touch.
> **Scope:** the Check-in Client — React Native (Expo) + TypeScript, **Check-in Staff only**. A Student never uses this app; their e-ticket is on the Web (`web-coding-standards.md`).
> **Authority:** `docs/technical-design/modules/checkin.md` §5–§8 for what the app must do offline; `architecture.md` §5.3 for why. Where this file and those disagree, they win.

## Rules at a Glance

1. **Layers, one way:** `app/` (Expo Router files, thin) → `src/app/` (composition root) → `src/features/*` → `src/shared`. A feature imports `shared` and itself, never another feature; from outside a feature only its `index.ts`. Enforced by ESLint (`eslint.config.mjs`).
2. **Verifying a ticket never touches the network, and never throws.** `verifyTicket` (`shared/ticket`) and `processScan` (`features/scan`) are plain functions of their arguments. No `fetch`, no `crypto.subtle`, no native module. The global `fetch` is banned by lint everywhere except `src/shared/api`.
3. **The algorithm is pinned in code, never read from the ticket.** ES256 only; the header's other members (`jwk`, `kid`, `jku`, `crit`) are never used to choose a key. One cached key, replaced — never two (`checkin.md` §6).
4. **A scan is written to SQLite before any result is shown.** If the write fails the screen says so and never says "valid".
5. **Secrets only in SecureStore:** the auth token, the cached verification key, the device id. Nothing secret in SQLite, AsyncStorage, a URL or a log. `console` is banned in `src` (lint); never log a token, a ticket or a scan payload.
6. **A verified scan keeps no signed payload.** Only the `ticket_code` from the verified payload is stored. A rejected scan keeps a ≤ 64-character preview and `ticketCode = null`; the server never receives rejected content as a code (D50).
7. **A scan is sent only by the session that made it** (`staff_user_id`, D53), and leaves the queue only when the response names its own `id` (D54). Anything else stays.
8. **No data is deleted by signing out, expiry or an error.** The queue, the key and the device id survive all three.
9. **Types from the contract:** `openapi-typescript` generates `src/shared/api/schema.gen.ts` (gitignored) from `packages/api-contract`; request/response types come only from it. A thin `fetch` client — no generated client.
10. **Pin every dependency exactly** (`save-exact`), at a version at least two weeks old; Expo packages at the versions `expo/bundledNativeModules.json` of the pinned SDK names.

## 1. Stack

| Concern | Choice | Why |
|---|---|---|
| Framework | Expo SDK 58 (`expo` 58.0.14), React Native 0.88.0, TypeScript strict | Runs in Expo Go on the owner's iPhone; no native build in Phase 5 |
| Routing | Expo Router (files under `app/`) | The standard for the SDK |
| Local database | `expo-sqlite` + Drizzle ORM, hand-written forward-only migrations (`src/shared/db/migrate.ts`, `PRAGMA user_version`) | The durable queue must survive a restart (`architecture.md` §5.3) |
| Secrets | `expo-secure-store` | Hardware-backed keystore |
| Camera | `expo-camera`, QR only | |
| Signature check | `@noble/curves` (P-256) — pure JavaScript | Hermes has no `crypto.subtle`; one code path on the phone and in Jest (checklist D58; `checkin.md` §6) |
| State | Zustand (the session), React state for the rest; TanStack Query is installed for server reads and unused until a read needs it | Same stack as the Web |
| Tests | Jest + `jest-expo` + React Native Testing Library; durable-queue tests on a real SQLite **file** via `better-sqlite3` | D57, D58 |

`expo` is pinned to **58.0.14**, not the first two-weeks-old 58.x (58.0.10): that one still pinned a React Native release candidate (0.88.0-rc.4), while Expo Go from the App Store runs the stable native runtime; 58.0.11 onward pin 0.88.0 (stable since 2026-10-15). `.npmrc` sets `legacy-peer-deps`: the SDK's packages declare optional peers (`react-dom`, `react-native-web`, `@expo/dom-webview` …) a native-only app does not install, and without the flag npm resolves the tree in a way that leaves `babel-preset-expo` out. `npx expo-doctor` passes 20/20.

## 2. Layout and dependency rule

```
app/                    Expo Router files: one line each, re-exporting a screen
src/app/                composition root: real database, keystore, network, clock (create-services.ts)
src/features/<name>/    scan, sign-in, rooms, history: screens and the logic only they use; index.ts is the API
src/shared/             api, auth (session, key, device id), crypto, db (schema, queue, rooms), secure, services, sync, ticket, ui, lib
src/testing/            fixtures: signing test tickets, a real SQLite file, fakes (only test files import it)
```

Anything two features need lives in `shared`. The scan queue repository, the session, the sync engine and the ticket verifier are in `shared` for that reason.

## 3. Offline behaviour (`checkin.md` §6–§8)

- **No key, no scanning.** A device that has never fetched the key shows no camera (`AC59.1`); the key is fetched at sign-in and after every successful sync, and a failed fetch keeps the old one.
- **The room is the workshop the staff member picked**, and is what `ScanEvent.workshopId` carries; a ticket of another workshop warns and is still recorded (D51).
- **Scanning the same ticket again on the same device warns and is recorded** — the server needs both to decide which came first.
- **The camera reports a code many times a second:** the same code within 2.5 s is one scan.
- **Sync triggers:** at start, when the network returns, when the app returns to the foreground, every minute while scans wait, and on the button. One sync runs at a time; batches are at most 500; an expired token (`401`) ends the session and keeps every scan.
- **The history** shows every scan, pending or synced, with the outcome the server decided; scans waiting for another staff member are listed as such.

## 4. Testing

- A test file sits next to the file it tests, `*.test.ts(x)`.
- **Tickets in tests are signed at run time from a throwaway key** (`src/testing/tickets.ts`). No ticket, JWT-shaped string or private key is committed: the secret scan would block it, rightly.
- The signature tests run over hundreds of tickets: ECDSA signatures are random, and a verifier that is wrong for half of them passes a one-ticket test by luck.
- The durable-queue tests close the SQLite connection and open the **file** again.
- What Jest cannot prove — the camera, Hermes, SecureStore, a real restart — is checked by hand on a phone and recorded in the Phase 5 evidence (`checkin.md` §6 spike S1, S2; checklist 8.0, 8.5, 8.6). A phase sign-off must say which of these were done.

## 5. Open items

| # | Item | Status |
|---|---|---|
| 1 | `crypto.subtle` on Hermes / `jose` (spike S1) | Not run on a device. The verifier does not depend on it (rule 2). If it did work, `jose` would still not be adopted: one pure-JS path is the point |
| 2 | Expo Go supports one SDK at a time | When Expo Go moves past SDK 58 the app upgrades or becomes a development build |
| 3 | Plain-HTTP access to a LAN address from Expo Go | To be confirmed on the phone (the API base URL is `http://<LAN address>:8080`) |
