# Phase 1 — Foundation: Checklist Triển Khai

> **Mục đích file này:** đây là checklist *thực thi* (implementation roadmap) cho Phase 1, dùng để cả người và Claude Code biết chính xác đang ở bước nào. Nó không thay thế `docs/testing/strategy/test-plan.md` (nguồn thẩm quyền cho exit criteria), `docs/testing/test-matrix.md` (nguồn thẩm quyền cho trạng thái từng test), hay `docs/testing/release-sign-offs/phase-template.md` (nguồn thẩm quyền cho sign-off cuối phase) — file này chỉ **tổng hợp và sắp thứ tự công việc**, trỏ vào 3 file đó làm bằng chứng. Nơi có mâu thuẫn, 3 file kia luôn đúng.
>
> **Cách dùng:** tick `[x]` khi hoàn thành, không tick trước khi xong thật (kể cả khi "chắc là đúng"). Mỗi mục nên có commit/PR tương ứng. Cập nhật cột **Trạng thái tổng thể** bên dưới sau mỗi buổi làm việc.

| Trường | Giá trị |
|---|---|
| Phase | 1 — Foundation |
| Ngày bắt đầu | 2026-09-25 |
| Trạng thái tổng thể | `☐ Chưa bắt đầu` / `☑ Đang làm` / `☐ Chờ sign-off` / `☐ Hoàn tất` |
| Nhánh làm việc | `phase-1-foundation` |
| Tham chiếu | `proposal.md` §15 (Phase 1 scope) · `test-matrix.md` M-01…M-17 · `coding-standards.md` (quy ước code test) · `mocking-guidelines.md`, `test-data-management.md` (hỗ trợ viết test) |

---

## 0. Tooling & Scaffolding (làm trước TDD, không viết logic nghiệp vụ ở bước này)

- [x] Cấu trúc Gradle **single-project** (Modular Monolith — KHÔNG multi-project) khớp ranh giới module trong `architecture.md` §1.1 (`com.unihub.user`, `com.unihub.workshop`, ...); ranh giới được enforce bằng `ArchitectureTest` (ArchUnit, `backend-coding-standards.md` §26.2 — 18 rule, xanh); `apps/backend/src/test/java` mirror `apps/backend/src/main/java` (`coding-standards.md` §1) — *Mục 0.1*
- [x] Checkstyle + Spotless cấu hình, chạy được qua `apps/backend/gradlew check` (lint job) — *Mục 0.2*
- [x] JaCoCo (`jacocoTestCoverageVerification`) cấu hình đúng scope `application.usecase`/`domain.*` package, ngưỡng 80% (`unit-testing.md` §3, `backend-coding-standards.md` §27 #2) — *Mục 0.3*
- [x] Hai Gradle task tách biệt: `test` (Unit, `*Test.java`, không Testcontainers) và `integrationTest` (`*IT.java`, Testcontainers) (`coding-standards.md` §1) — *Mục 0.4*
- [x] `docker-compose.yml` cho local dev (Postgres, Redis, RabbitMQ) — tách biệt với Testcontainers — *Mục 0.5*
- [x] Flyway/Liquibase migration setup, chạy được trên Postgres local
- [x] Wire `packages/api-contract` vào build: validate + bundle spec (DTO viết tay theo §10, verify bằng `contract-diff` ở Mục 1, không codegen) — *Mục 0.7*
- [x] `generateOpenApiDocs` (springdoc) task hoạt động, sinh `build/openapi.json` từ controller annotation — *Mục 0.8*
- [x] GitHub Actions workflow file khởi tạo: tối thiểu job `lint` + `unit-test`, xanh trên 1 commit trống/hello-world — *Mục 0.9*
- [ ] React + TypeScript (Vite) scaffold cho **Web Application**: TanStack Query, Zustand, Tailwind CSS, Shadcn UI, React Hook Form + Zod cài đặt
- [ ] Vitest + React Testing Library cấu hình cho FE component test (`e2e-testing.md` §1)

> ⚠ **Không** khởi tạo Mobile Application (React Native/Expo) ở Phase 1 — đó là Phase 5, chỉ dành cho Check-in Client.

---

## 1. Walking Skeleton (lát cắt mỏng xuyên suốt kiến trúc)

- [ ] Một endpoint tối giản (vd. `GET /workshops` trả danh sách rỗng) đi hết: Controller → Service → Repository → Postgres thật
- [ ] Integration test (`*IT.java`) chứng minh endpoint chạy được với DB thật (Testcontainers)
- [ ] Web app gọi được endpoint này và render kết quả (component test tương ứng)
- [ ] `contract-diff` chạy và pass cho path này (đường ống hand-written spec ↔ generated spec hoạt động — `contract-testing.md` §4)
- [ ] Toàn bộ pipeline (`lint → unit-test → integration-test → contract-diff`) xanh trên 1 PR cho lát cắt này

*Mục tiêu: xác nhận sớm mọi giả định kiến trúc (JWT stateless, routing, DB connection, CORS, contract-diff) trước khi đổ effort TDD sâu vào nghiệp vụ.*

---

## 2. TDD theo thứ tự phụ thuộc nghiệp vụ

Với mỗi mục bên dưới: viết test trước (Red) → code tối thiểu để pass (Green) → refactor → nếu cần, viết thêm Integration test (`*IT.java`) → cập nhật `test-matrix.md` (`Not started` → `Drafted` → `Passing`, điền `Class#method` thật).

### 2.1 Domain Model
- [ ] Entity `User`, `Role`, `Permission`, `Workshop` + migration script tương ứng

### 2.2 RBAC / JWT — INV-01
- [ ] **M-01** — Users–roles–permissions mapping, permission set cấu hình lại không cần sửa code
- [ ] **M-02** — JWT mang permission list đã resolve; token giả mạo/hết hạn/sai `alg` bị từ chối không side-effect
- [ ] **M-03** — Endpoint access quyết định bởi `hasAuthority(permission)`, không theo tên role; `401` vs `403` không bị lẫn

### 2.3 Workshop CRUD (Organizer/Admin)
- [ ] **M-04** — Tạo/sửa/hủy/dời lịch workshop (state transition, terminal states `COMPLETED`/`CANCELLED` phải reject)

### 2.4 Workshop Listing/Search/Detail (Student)
- [ ] **M-05** — Duyệt/tìm/lọc workshop theo lịch, chủ đề, speaker, địa điểm
- [ ] **M-06** — Trang detail hiển thị speaker/địa điểm/số ghế còn; workshop `DRAFT` trả `404` cho người không đủ quyền

### 2.5 Web Application Scaffold
- [ ] **M-07** — Component list + detail render đúng dữ liệu từ 2 endpoint trên (Vitest + RTL)

### 2.6 Hoãn lại hợp lệ (không phải invariant, không nằm trên critical path — `test-plan.md` §2)
- [ ] **M-08** — Cache Redis cho dữ liệu workshop ít đổi → *có thể hoãn, đánh dấu lý do trong `test-matrix.md` §4 nếu hoãn*
- [ ] **M-09** — Nightly legacy sync (validate-then-write pipeline) → *có thể hoãn*
- [ ] **M-10** — Error store cho record lỗi/trùng trong batch sync → *có thể hoãn*
- [ ] **M-11** — Nightly sync không gián đoạn user đang hoạt động → *có thể hoãn*

---

## 3. Hàng cấu trúc / phi chức năng của Phase 1

- [ ] **M-12** — Backend tách module rõ ràng, logic core decoupled khỏi hạ tầng (kiểm tra thủ công qua cấu trúc package)
- [ ] **M-13** — Secrets/config không nằm trong source, inject lúc runtime (Gitleaks scan xanh)
- [ ] **M-15** — Coverage ≥80% trên core business-logic service (JaCoCo gate xanh)
- [ ] **M-16** — Integration test dùng Postgres/Redis/RabbitMQ thật qua Testcontainers, **không** dùng H2 hay in-memory DB
- [ ] **M-17** — Mọi response API tuân theo shape thống nhất (success/conflict/failure) — kiểm bằng contract-diff

---

## 4. CI/CD — hoàn thiện toàn bộ pipeline

- [ ] **M-14** — Job `lint` → `unit-test` → `integration-test` → `contract-diff` chạy tuần tự, có gate, trên mọi push/PR vào `main`
- [ ] `unit-test` gated bởi `lint`
- [ ] `integration-test` gated bởi `unit-test` thành công (Testcontainers job chạy sau cùng để không lãng phí thời gian)
- [ ] `contract-diff` gated bởi `integration-test` thành công
- [ ] Gitleaks secret scan chạy như 1 stage riêng (liên quan M-13)
- [ ] Branch protection rule: merge vào `main` bị chặn nếu bất kỳ stage nào fail

---

## 5. Đồng bộ Test Matrix

- [ ] Sau mỗi mục ở Mục 2–4 hoàn tất: cập nhật đúng dòng tương ứng trong `docs/testing/test-matrix.md` §2.2 (Status, Test case(s) = `Class#method` thật)
- [ ] Cập nhật `test-matrix.md` §4 (Coverage Gaps) và §5 (Reference Table) trước khi sign-off

---

## 6. Đóng Phase 1 (Exit / Sign-off)

- [ ] Toàn bộ dòng M-01…M-17 trong `test-matrix.md` = `Passing` (trừ các dòng đã hoãn hợp lệ ở Mục 2.6, ghi rõ lý do)
- [ ] Coverage ≥80% trên package `service`/`domain` (JaCoCo xanh)
- [ ] CI xanh toàn bộ (`lint`, `unit-test`, `integration-test`, `contract-diff`, Gitleaks) trên **cùng một commit SHA**
- [ ] Không còn `TODO`/`FIXME` trong test code của Phase 1
- [ ] Không có `@Disabled` trên test gắn với INV-01
- [ ] Copy `docs/testing/release-sign-offs/phase-template.md` → `docs/testing/release-sign-offs/phase-1.md`, điền đầy đủ §2–§6, tick từng ô với evidence thật
- [ ] Ký xác nhận ở §6 của `phase-1.md`

> Phase 1 chỉ được coi là hoàn tất khi `phase-1.md` tồn tại, mọi ô được tick với evidence thật, và §6 đã ký — **không phải khi code chạy được**.

---

## Ghi chú cho Claude Code

- Khi được yêu cầu "tiếp tục Phase 1", đọc file này trước, tìm mục đầu tiên chưa tick theo đúng thứ tự Mục 0 → 1 → 2 → 3 → 4 → 5 → 6, và làm mục đó.
- Không tự ý tick một mục — chỉ tick sau khi test thật sự pass và (với các mục ở Mục 2) `test-matrix.md` đã được cập nhật tương ứng.
- Không nhảy cóc sang Mục 2.6 (các mục hoãn được) trước khi Mục 2.1–2.5 xong, trừ khi người dùng yêu cầu rõ ràng.
