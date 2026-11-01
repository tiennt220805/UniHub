# UniHub Check-in Client

React Native (Expo) app for **Check-in Staff**: signs in, scans e-ticket QR codes **offline**, keeps every
scan in a durable SQLite queue and syncs it when there is a network. The student never uses it.
Standards: `docs/guidelines/mobile-coding-standards.md`. Design: `docs/technical-design/modules/checkin.md`.

## Run it on an iPhone (Expo Go)

1. Install **Expo Go** from the App Store. It must support Expo SDK 58.
2. Start the backend (`apps/backend`: `docker compose up -d`, then `./gradlew bootRun` with the environment
   in `env.example`; the `dev` profile seeds students, and a Check-in Staff account is needed — see below).
3. Find the computer's address on your Wi-Fi (for example `192.168.1.10`) and allow port 8080 through the
   macOS firewall. The phone and the computer must be on the same network.
4. In this folder:

   ```sh
   cp .env.example .env        # then set EXPO_PUBLIC_API_BASE_URL to http://<that address>:8080
   npm ci
   npm run api:types
   npx expo start
   ```

5. Scan the QR code shown in the terminal with the iPhone camera; it opens in Expo Go.

A Check-in Staff account: the `dev` profile seeds students only. For local use insert a staff user (the password
below is only an example; the hash is bcrypt):

```sh
HASH=$(htpasswd -nbBC 10 "" 'DevPass-12345' | cut -d: -f2 | sed 's/^\$2y/$2a/')
docker exec unihub-postgres psql -U unihub -d unihub -c "
  insert into users (email, password_hash, full_name) values ('staff1@dev.unihub.local', '$HASH', 'Dev Check-in Staff 1');
  insert into user_roles (user_id, role_id) select u.id, r.id from users u, roles r
    where u.email = 'staff1@dev.unihub.local' and r.name = 'Check-in Staff';"
```

The checks that need a phone are listed in `docs/testing/reports/phase-5-device-checks.md`.

## Commands

|                     |                                                                         |
| ------------------- | ----------------------------------------------------------------------- |
| `npm run typecheck` | `tsc --noEmit`                                                          |
| `npm run lint`      | oxlint, ESLint (layer rules, no `console`, no global `fetch`), Prettier |
| `npm test`          | Jest: ticket verification, the SQLite queue, sync, screens              |
| `npm run api:types` | regenerates `src/shared/api/schema.gen.ts` from `packages/api-contract` |
