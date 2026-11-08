# UniHub Check-in Client

React Native (Expo) app for **Check-in Staff**: signs in, scans e-ticket QR codes **offline**, keeps every
scan in a durable SQLite queue and syncs it when there is a network. The student never uses it.
Standards: `docs/guidelines/mobile-coding-standards.md`. Design: `docs/technical-design/modules/checkin.md`.

## Prerequisites

- Node.js 22 and npm
- An iPhone with **Expo Go** from the App Store (it must support Expo SDK 58), on the same Wi-Fi network as your computer
- The Backend API running locally with a Check-in Staff account (below)

## Run it on an iPhone (Expo Go)

1. **Start the backend** by following [`apps/backend/README.md`](../backend/README.md). In the backend's `.env`, set `UNIHUB_DEV_SEED_CHECKIN_STAFF_PASSWORD` before the first run with `SPRING_PROFILES_ACTIVE=dev`: that seeds the Check-in Staff account `staff1@dev.unihub.local` with the password you chose. The backend's management port is `8081`, which is also Expo's default Metro port, so start Expo on another port (step 3) and leave the backend alone.
2. Find the computer's address on your Wi-Fi (for example `192.168.1.10`) and allow port 8080 through the macOS firewall. The phone and the computer must be on the same network.
3. In this folder:

   ```sh
   cp .env.example .env        # then set EXPO_PUBLIC_API_BASE_URL to http://<that address>:8080
   npm ci
   npm run api:types
   npx expo start --port 8082  # not 8081: the backend's management port
   ```

4. Scan the QR code shown in the terminal with the iPhone camera; it opens in Expo Go.
5. Sign in as `staff1@dev.unihub.local`. To have a ticket to scan, register a student for a workshop in the web app ([`apps/web/README.md`](../web/README.md)) and open the e-ticket page, which shows the QR code.

The checks that need a phone are listed in `docs/testing/reports/phase-5-device-checks.md`.

## Commands

|                     |                                                                         |
| ------------------- | ----------------------------------------------------------------------- |
| `npm run typecheck` | `tsc --noEmit`                                                          |
| `npm run lint`      | oxlint, ESLint (layer rules, no `console`, no global `fetch`), Prettier |
| `npm test`          | Jest: ticket verification, the SQLite queue, sync, screens              |
| `npm run api:types` | regenerates `src/shared/api/schema.gen.ts` from `packages/api-contract` |

The app is run in Expo Go and has no store build: publishing to the App Store or Google Play is out of scope.
