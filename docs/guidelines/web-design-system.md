# Web Design System — UniHub

The visual language of `apps/web`: tokens, type, spacing, motion, state rules, and which UI libraries are allowed. It is the
owner of *how the Web looks*; `web-coding-standards.md` owns *how the code is laid out* and links here (§15). Decisions are those of
the Phase 8 checklist (`checklists/phase-8-web-ui.md`, D105–D129); this file does not restate them, it makes them usable.

> **Status.** Sections marked *proposed* are the values the G1 mockup shows; they become final when the owner approves G1 (D116).
> Everything else is implemented in `apps/web/src/styles/index.css` and checked by tests.

## 1. Principles

1. **Young and energetic, still a university system, never loud** (D109). Green carries the brand; amber is a rare highlight.
2. **No identity borrowed from a school** (D108). UniHub has its own.
3. **Accessible by default**: WCAG 2.2 AA is the floor (D115). Text 4.5:1; control borders and the focus ring 3:1; state is never carried by colour alone.
4. **Phone first for students, desktop first for administration** (D112). Student pages work from 360 px; `/admin` is usable from 768 px and tuned for 1280 px (D128).
5. **Calm motion** (D113): under 300 ms, with a purpose, and off when the user asks (§6).
6. **No business knowledge in `shared/ui`** (web-coding-standards.md Rules #4): components know colours and behaviour, not statuses or permissions.

## 2. Colour tokens (palette B: forest green + amber, D110)

Tokens are CSS variables on `:root` (light) and `.dark` (dark) in `src/styles/index.css`, mapped to Tailwind colours by `@theme inline`.
**Dark mode is the `.dark` class on `<html>` and nothing else**: there is deliberately no `prefers-color-scheme` block, because it would
beat a user who chose "Sáng" on a machine that is in dark mode. `public/theme-init.js` sets the class before first paint (D124);
the store in `shared/store/theme-store.ts` keeps the choice (`light` / `dark` / `system`, default `system`).

| Token | Light | Dark | Use |
|---|---|---|---|
| `background` / `foreground` | `#FAFAF7` / `#14261B` | `#0A120D` / `#E7F0E9` | page |
| `card` / `card-foreground`, `popover` / `popover-foreground` | `#FFFFFF` / `#14261B` | `#111B15` / `#E7F0E9` | surfaces |
| `primary` / `primary-foreground` | `#15803D` / `#FFFFFF` | `#4ADE80` / `#052E16` | the one main action |
| `primary-hover` | `#126F33` | `#86EFAC` | hover of `primary` (see §8) |
| `primary-text` | `#166534` | `#4ADE80` | links, dates and times on a page surface |
| `primary-soft` | `#DCFCE7` | `#103A20` | tinted background behind `primary-text` |
| `secondary`, `muted`, `accent` | `#EEF3EC`, `#ECEFEA`, `#E6EFE7` | `#17251C`, `#1D2A21`, `#1C2D22` | quiet surfaces; `accent` is the hover/selected surface of ghost buttons, menus and list items |
| `muted-foreground` | `#566358` | `#9BAD9F` | secondary text |
| `highlight` / `highlight-foreground` | `#FCD34D` / `#451A03` | same | amber chip, the "Có phí" label. **Palette B's "accent"** (see §8) |
| `success` / `success-soft` | `#166534` / `#DCFCE7` | `#4ADE80` / `#103A20` | |
| `warning` / `warning-soft` | `#C2410C` / `#FFEDD5` | `#FDBA74` / `#3A1F08` | |
| `destructive` / `destructive-soft` | `#B91C1C` / `#FEE2E2` | `#F87171` / `#3B1414` | |
| `info` / `info-soft` | `#1D4ED8` / `#DBEAFE` | `#93C5FD` / `#14243F` | |
| `border` | `#E1E6DF` | `#223127` | decoration only (cards, dividers), **not** a control edge |
| `input` | `#7F8C82` | `#627566` | the border of every form control |
| `ring` | `#15803D` | `#4ADE80` | focus ring (solid, never `/50`) |
| `seat-ok` / `seat-low` / `seat-track` | `#15803D` / `#EA580C` / `#ECEFEA` | `#4ADE80` / `#FB923C` / `#1D2A21` | the seat bar |
| `chart-1 … 5` | provisional | provisional | final series colours are chosen with the report charts (checklist 6.8) |
| `sidebar*` | derived from the set above | | admin sidebar |

### Measured contrast

Computed from `index.css` by `vite-plugins/__tests__/design-tokens-contrast.test.ts` (61 tests; it also checks the destructive
button's tinted surface and that both modes define the same tokens). A pair that drops below its floor fails the build.

| Pair | Light | Dark | Floor |
|---|---|---|---|
| `foreground` / `background` | 15,18 | 16,32 | 4,5 |
| `card-foreground` / `card` | 15,88 | 15,14 | 4,5 |
| `muted-foreground` / `background` | 6,05 | 8,03 | 4,5 |
| `muted-foreground` / `card` | 6,32 | 7,45 | 4,5 |
| `muted-foreground` / `muted` | 5,45 | 6,31 | 4,5 |
| `primary-foreground` / `primary` | 5,02 | 8,55 | 4,5 |
| `primary-foreground` / `primary-hover` | 6,27 | 10,62 | 4,5 |
| `primary-text` / `background` | 6,82 | 10,90 | 4,5 |
| `primary-text` / `primary-soft` | 6,49 | 7,31 | 4,5 |
| `highlight-foreground` / `highlight` | 10,39 | 10,39 | 4,5 |
| `warning` / `warning-soft` | 4,52 | 9,03 | 4,5 |
| `destructive` / `destructive-soft` | 5,30 | 5,86 | 4,5 |
| `info` / `info-soft` | 5,49 | 8,60 | 4,5 |
| `input` / `background` | 3,36 | 3,85 | 3,0 |
| `input` / `card` | 3,51 | 3,57 | 3,0 |
| `ring` / `background` | 4,80 | 10,90 | 3,0 |
| `ring` / `card` | 5,02 | 10,12 | 3,0 |
| `seat-ok` / `seat-track` | 4,32 | 8,58 | 3,0 |
| `seat-low` / `seat-track` | 3,07 | 6,60 | 3,0 |

The preview page the owner chose drew the input border as `#CDD6CC` / `#2D3F33` (1,49 / 1,57), which fails 3:1; it was replaced
by `input` above (C71).

## 3. Typography *(proposed, final at G1)*

**Be Vietnam Pro**, self-hosted through `@fontsource/be-vietnam-pro` 5.3.0, weights 400, 500, 600, 700 (D111). Each weight's CSS carries the
`vietnamese`, `latin-ext` and `latin` subsets with `unicode-range`, so a Vietnamese page loads only what it uses. The font files
are served from the app's own origin (`font-src 'self'`); the build must emit no `data:font` (checked at 2.2). Fallback stack:
`ui-sans-serif, system-ui, sans-serif`. `<html lang="vi">`.

| Role | Size / line height | Weight | Notes |
|---|---|---|---|
| Display (home hero) | 36 / 44, 48 / 56 from `lg` | 700 | one per page |
| H1 | 30 / 38 | 700 | page title |
| H2 | 24 / 32 | 600 | section |
| H3 | 20 / 28 | 600 | card or panel title |
| Body | 16 / 24 | 400 | never below 16 px for a form control, or iOS zooms the page on focus |
| Small | 14 / 20 | 400, 500 | metadata, table cells |
| Caption | 12 / 16 | 500 | the floor; badges and axis labels only |

Vietnamese stacks diacritics above and below the line: keep body line height at 1.5 or more and headings at 1.25 or more, and
test long titles with tone marks (checklist 7.2). Numbers that change (seat counts, countdown) use tabular figures
(`font-variant-numeric: tabular-nums`) so they do not jitter.

## 4. Spacing, radius, elevation *(proposed, final at G1)*

- **Spacing:** Tailwind's 4 px scale. Page gutter 16 px below `md`, 24 px from `md`. Student content `max-w-6xl`; the catalog grid is 1 column at 360 px, 2 from `sm`, 3 from `lg`. Admin content `max-w-5xl` inside the sidebar layout. Touch targets 44 px minimum on student pages (a 36 px button gets padding, not a smaller hit area).
- **Radius:** `--radius` is 0.625 rem (10 px). Cards `rounded-xl` (14 px), buttons and controls `rounded-lg`, chips and badges `rounded-full`, dialogs `rounded-2xl`.
- **Elevation:** light mode cards use `border` plus `shadow-sm`; popovers and menus `shadow-md`. Dark mode drops shadows (they vanish on `#0A120D`) and relies on `border` and the lighter `card`.

## 5. State is never colour alone (D115)

Primary and "success" share a hue, so a status must carry text or an icon besides its colour. Every status component takes its text from a
map in its **feature** (never from `shared/`); `shared/ui` `Badge` only knows the tone. The icon and tone columns are *proposed*, final at G1.
The **text is not free**: where it already exists in the code it stays, because Playwright asserts some of it (C68).

| Domain value (API contract) | Tone | Text | Source of the text | Icon |
|---|---|---|---|---|
| `RegistrationStatus.CONFIRMED` | success | Đã xác nhận | existing, `registration-status.ts` | check-circle |
| `RegistrationStatus.RESERVED` | warning | Đang giữ chỗ | existing | clock |
| `RegistrationStatus.EXPIRED` | muted | Hết hạn giữ chỗ | existing | timer-off |
| `RegistrationStatus.CANCELLED` | destructive | Đã hủy | existing | x-circle |
| `PaymentStatus.PENDING` | info | Đang xử lý thanh toán… | existing notice, `PaymentStatusNotice.tsx` | loader |
| `PaymentStatus.SUCCEEDED` | success | Thanh toán thành công. | existing notice (**asserted by E2E**) | check-circle |
| `PaymentStatus.FAILED` | destructive | Thanh toán bị từ chối. Bạn có thể thử lại bằng phương thức khác. | existing notice | x-circle |
| `PaymentStatus.CANCELLED` | warning | Thanh toán đã bị hủy. Bạn có thể thử lại. | existing notice | ban |
| `PaymentStatus.REFUND_REQUIRED` | warning | Thanh toán đã thành công nhưng đăng ký không còn hiệu lực. Khoản tiền sẽ được hoàn lại. | existing notice | undo |
| `PaymentStatus.REFUNDED` | info | Khoản thanh toán đã được hoàn lại. | existing notice | check-circle |
| `WorkshopStatus.CANCELLED` / `COMPLETED` | destructive / info | Workshop này đã bị hủy. / Workshop này đã kết thúc. | existing notice, `WorkshopDetailView.tsx` | x-circle / flag |
| `WorkshopStatus.DRAFT` / `PUBLISHED` | muted / success | Nháp / Đã công bố | **proposed** badge text for the admin list (S10); no existing label | pencil / globe |
| Seat level (catalog, D118) | success / warning / destructive | Còn chỗ / Sắp hết / Hết chỗ | owner's wording in D118 | none beyond the bar |
| Ticket type | highlight / muted | Có phí / Miễn phí | existing in `MyRegistrationList.tsx`; `Có phí` is **asserted by E2E** (`student-journey.spec.ts`) | none |

Strings the Playwright suites assert and that therefore may not change without the spec in the same commit (C68): `Đăng ký tham dự`,
`Đăng ký thành công. Chỗ của bạn đã được xác nhận.`, `Xem vé`, `Mã QR e-ticket` (image name), `Thanh toán` (heading and button),
`Thanh toán thành công.`, `Đăng ký của bạn đã được xác nhận.`, `Xem đăng ký của tôi`, `Có phí`, `Đăng nhập để đăng ký`, `Đăng nhập`,
`Đăng xuất`, `Đăng ký của tôi` (link), `Xem báo cáo`, the region `Báo cáo workshop` and its figures `Sức chứa`, `Chỗ đã giữ`,
`Đăng ký đã xác nhận`. A label approved at a gate that differs from one of these is changed in the spec in the same commit as the screen.

The seat level on the catalog is "about" (the list is cached for 30 s); only the detail page shows the live number. Errors sit next to
their field and say what to do; a `503` offers "Thử lại".

---

## 6. Motion (D113)

Rules, from the Emil Kowalski set the checklist names (`.claude/skills/emil-design-eng`):

1. **Ask first whether it should animate.** Frequent or keyboard-driven actions do not (opening the menu from the keyboard, tabbing, typing in search). Live data does not animate either: the seat count, the countdown and the payment status change in place (and never optimistically, Rules #8).
2. **Under 300 ms, and exits faster than entrances.** Press feedback 100–160 ms, tooltips and popovers 125–200 ms, menus 150–250 ms, dialogs and sheets 200–300 ms.
3. **Easing:** entering or leaving uses ease-out; on-screen movement uses ease-in-out; never ease-in.

   ```css
   --ease-out: cubic-bezier(0.23, 1, 0.32, 1);
   --ease-in-out: cubic-bezier(0.77, 0, 0.175, 1);
   ```
4. **Only `transform` and `opacity`**; name the property (`transition: transform 160ms var(--ease-out)`, never `all`). Entrances start from `scale(0.95)` with `opacity: 0`, never `scale(0)`. Popovers grow from their trigger; modals stay centred.
5. **Transitions, not keyframes**, for anything that can be triggered quickly (they retarget; keyframes restart).
6. **Pressable things** get `scale(0.97)` on `:active`. Hover effects sit behind `@media (hover: hover) and (pointer: fine)` so a tap does not leave a stuck hover.
7. **`prefers-reduced-motion: reduce`** removes movement and keeps opacity and colour changes (fewer and gentler, not zero). Tailwind's `motion-safe:` / `motion-reduce:` variants carry it.

## 7. Libraries — what the spike allowed (checklist 2.3, Q9, D125)

Each candidate was opened on Chromium, Firefox and WebKit under the production policy (`style-src 'self'`, no `'unsafe-inline'`) by
`tests/e2e/specs-local/spike.spec.ts` (`npm run test:local` in `tests/e2e`), which also checks the component appeared and is styled.

| Library | Result | Consequence |
|---|---|---|
| Radix Tabs, Popover, DropdownMenu, Dialog, **AlertDialog**, Sheet (through `radix-ui` and the shadcn files in `shared/ui`) | pass, **with the scroll-lock shim** (AlertDialog: opens, locks scroll, both buttons close it, 0 violations on all three engines) | usable |
| Native `<input type="date">` and `datetime-local` (no library) | pass: take a value, styled by our classes, **0 reports on Chromium, Firefox and WebKit** | the date range of the catalog filter and the admin form's `startTime`/`endTime` use native inputs; no `DateTimePicker` library. The picker popup is the operating system's and cannot be themed |
| recharts 3.10.1 | pass (bars and donut, 0 violations) | the report charts (6.8) |
| **Sonner** | **fail**: injects two `<style>` elements at import time; the toaster stays `position: static` | removed; the toast is built in `shared/ui` (2.6) |
| **NumberFlow** | **fail**: injects a `<style>` when the page loads; digit columns render raw | removed; no rolling-digit animation |
| **Radix Select** | **fail**: `SelectViewport` always renders a `<style>` element; every open is a violation | not used. If a screen needs one: a hash in `style-src` (owner), or a native `<select>` (and WebKit reports `style-src-attr` for every native `<select>`, the checkout exception of 2026-11-06, which the owner would also have to widen) |

**The scroll-lock shim.** Radix's modal primitives lock page scroll with `react-remove-scroll`, which injects a `<style>` through
`react-style-singleton`; every modal open was a violation on all three engines. `src/shared/lib/csp-safe-remove-scroll.tsx` does the
same through the CSSOM (`body.style`, which the policy allows), and `vite.config.ts` aliases the package name to it, so Radix is not
patched. Given up: the real package also swallows wheel and touch events aimed at scrollable elements *outside* the open layer. An
`AlertDialog` is built on the same primitive and passed the spike too.

Every new UI library goes through the spike first. A library that needs a CSP exception is not added without the owner (checklist, "Ghi chú cho Claude Code").

## 8. Where this departs from the shadcn defaults, and from the preview page

| What | Why | Where decided |
|---|---|---|
| shadcn's `--accent` is a **green surface**, amber is `--highlight` | shadcn uses `accent` for hover and selection (ghost buttons, menu items, list rows); amber there would paint every hovered item yellow, against D109. The hex values of palette B are unchanged | `index.css`; confirm at G1 |
| `--primary-hover` is its own token | `hover:bg-primary/80` of the stock button drops white-on-green below 4,5:1 in light mode | `index.css`; 2.6 must use it |
| Focus ring is a solid `outline-ring`, not `/50` | half opacity falls under 3:1 | `index.css` base layer |
| `muted` and `seat-track` are `#ECEFEA` / `#1D2A21` | from the owner's preview page; they reproduce the 4,32 and 3,07 of the contrast table | `index.css` |
| `secondary`, `accent`, `sidebar*`, `chart-*` are derived, not given | the table did not define them | `index.css`; `chart-*` final at 6.8 |
| Dark mode is the `.dark` class only | a toggle cannot lose to the operating system | `index.css`, `theme-store.ts` |

## 9. Components (`apps/web/src/shared/ui`)

Present: `button` (the stock shadcn one, restyled at 2.6), `theme-toggle`, and the unstyled shadcn primitives from the spike (`dialog`,
`sheet`, `popover`, `dropdown-menu`, `tabs`). Present too, unstyled: `alert-dialog`. Planned at 2.6, after G1: Input (including the native date and datetime-local variants, §7), Textarea, Badge, Card, Skeleton,
EmptyState, ErrorState, Toast (own, because of §7), Pagination. There is no `Select` (§7) and no `DateTimePicker` library. Each one knows tokens and behaviour only; a status, a permission or a
Vietnamese domain phrase lives in the feature that uses it.
