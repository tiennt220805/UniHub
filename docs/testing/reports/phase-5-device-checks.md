# Phase 5 — checks that need a phone

> The Check-in Client's automated tests (Jest) run on Node. They cannot show that the app starts under Hermes, that the camera reads a QR, that SecureStore keeps a value across a restart or that a phone reaches the backend. Those four are checked by hand, once, on the owner's iPhone in Expo Go, and written down here. **A Phase 5 sign-off must say which rows are filled; an empty row is a gap, not a pass** (Phase 5 checklist 8.0, 8.3, 8.5, 8.6; `checkin.md` §6; `mobile-coding-standards.md` §4).

Set-up: `apps/mobile/README.md`. Two things that bite:

- **Tickets die when the backend restarts without a fixed key.** `bootRun` with a key made on the spot (`scripts/generate-ticket-key.sh` without `--write`) signs with that key only until it stops; a restart with a new key makes every earlier ticket fail on the phone, correctly. Register the student for the workshop **after** the last restart, then scan.
- **The phone's token lasts 30 minutes, and that is not a limit on scanning:** a session whose token has expired stays signed in on the device (also after the app is closed), keeps scanning, and ends only when a sync is refused with `401` — every scan is kept (`checkin.md` §7). Fill in the date, the person, the Expo Go version, the iPhone model and iOS version.

| Field | Value |
|---|---|
| Date / person | |
| Expo Go version (Settings in Expo Go) and SDK it runs | |
| iPhone model, iOS version | |
| Backend | local `bootRun`, `EXPO_PUBLIC_API_BASE_URL` = |

## S1 — the app runs, and verifies a real ticket, on Hermes (AC58.1)

| # | Check | Result | Notes |
|---|---|---|---|
| S1.1 | The app opens in Expo Go and shows the sign-in screen | | |
| S1.2 | Sign-in with a Check-in Staff account succeeds and the scan screen appears (this loads the verification key) | | |
| S1.3 | A Student account is refused with "không phải nhân viên check-in" | | |
| S1.4 | Pick a room; scan the e-ticket QR of a student registered for that workshop (shown on the Web page `/me/registrations/:id`): **"Hợp lệ (chờ đối soát)"** with the ticket code | | |
| S1.5 | Scan something that is not a UniHub ticket (any other QR): **"Vé không hợp lệ"** | | |
| S1.6 | Optional: in a Metro console `log` (development only, then remove), `typeof globalThis.HermesInternal` and `typeof globalThis.crypto?.subtle` | | |

## S2 — the queue survives the app being killed (AC58.1, M-44)

| # | Check | Result | Notes |
|---|---|---|---|
| S2.1 | Turn on airplane mode (Wi-Fi off). Scan two tickets (a valid one and an invalid one): both appear in "Lịch sử quét" as "Chờ gửi" | | |
| S2.2 | Force-quit Expo Go (swipe it away), open the app again: still signed in, "2 lần quét chờ gửi", both scans still in the history | | |
| S2.3 | Scan the first ticket again: "Vé này đã quét lúc hh:mm" and the count is 3 | | |

## 8.6 — device to backend

| # | Check | Result | Notes |
|---|---|---|---|
| D1 | Turn Wi-Fi back on: within about a minute (or at once with "Đồng bộ ngay") the count drops to 0 | | |
| D2 | The history shows each scan as "Đã gửi" with the server's outcome | | |
| D3 | In Postgres: `select id, registration_id, outcome, scanned_by, device_id from check_in_scans order by scanned_at desc limit 5;` has the three scans, `scanned_by` = the staff user, one `ACCEPTED`, one `REJECTED_DUPLICATE`, one `REJECTED_INVALID` with a null `registration_id` | | |
| D4 | `select * from attendances` has one row for the valid ticket | | |
| D5 | Sign out with a scan queued: the app warns first and keeps the scan | | |

## Not covered by any row above (stated, not hidden)

- Android: not tried.
- A standalone build (EAS preview, TestFlight): not part of Phase 5.
- Expo Go moves to a newer SDK over time; this file records the version it was done with.
