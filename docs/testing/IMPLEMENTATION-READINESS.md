Tạo ngày 2026-09-24 — nguồn tham chiếu duy nhất cho các mục còn mở khi bước vào implementation — cập nhật tại đây khi một mục được giải quyết, không tạo checklist mới ở nơi khác.

# Implementation Readiness Checklist — UniHub

Ký hiệu: **Q** = cần bạn/team quyết; **V** = verify lúc implementation; **D** = còn thiếu thiết kế.

## A. Precondition Phase 7 sign-off (`strategy/test-plan.md` §1)

| # | Mục | Loại |
|---|---|---|
| A1 | P1: alert bù cho Redis/RabbitMQ degraded — **đã định nghĩa 2026-11-02** (Phase 6 D77: `unihub_dependency_up` từ probe nền, rule `== 0` 1m Critical, `observability.md` §4/§6); xây ở Phase 6, đạt khi chạy trên deploy Phase 7 | D |
| A2 | P2: hop Ingress → `backend-api` là mTLS — từ 2026-11-05 Ingress là Istio ingress gateway (Phase 7 checklist D83; ingress-nginx đã ngừng bảo trì), thành viên mesh do thiết kế, không cần exception | V |
| A3 | P3: native sidecar chạy thật trên Istio 1.31.x + K8s 1.35.1 (Phase 7 checklist D81; 1.24.x đã hết hỗ trợ) cho Job Flyway và init container replica (và có phải bật thủ công không); ảnh hưởng cả deploy PERMISSIVE | V |
| A4 | P4: dựng stack Prometheus/Grafana/Tempo rồi inject hoặc scoped exception (manifest chưa tồn tại) | V/D |

## B. Thiết kế còn thiếu

| # | Mục | Loại |
|---|---|---|
| B1 | 1c: health-check điều khiển fallback Replica-unreachable → Master (hướng đã duyệt, cơ chế chưa có; `overlays/dev` 0 replica giải quyết theo) | D |
| B2 | INV-07 với Redis outage dài hơn retry chain (~5m35s): hold-expiry bị đưa vào parking lot | Q |
| B3 | Rule alert cho audit-publish failure — **rule đã định nghĩa 2026-11-02** (`observability.md` §6, Phase 6 D77); bằng chứng là `AuditAlertIT` (Phase 6 D72), viết xong 2026-11-04 (Prometheus + Grafana thật, rule `Firing`, 10/10 lần local) | D |
| B5 | Cách backend lấy `ipAddress` phía sau Ingress (ảnh hưởng limiter, load test, S-16) | Q |

## C. Tham số load-test (`non-functional/performance-load.md`)

| # | Mục | Loại |
|---|---|---|
| C1 | Diễn giải "12,000 concurrent" (hỏi tác giả `requirement.md` §1.4) | Q |
| C2 | Journey mix, think time, tỷ lệ free/paid, paid có gọi `charge` không | Q |
| C3 | Số workshop, capacity, tổng ghế | Q |
| C4 | Latency và vị trí đặt WireMock stub | Q |
| C5 | Ngưỡng error rate, định nghĩa "remains available", latency của `409`, endpoint nào là "registration-confirmation" | Q |
| C6 | Cách xử lý limiter per-IP khi chạy K6 từ một nguồn | Q |
| C9 | Tải của việc poll số ghế (`staleTime` 3s / `refetchInterval` 5s khi tab hiển thị — ~2.400 rps ở 12.000 CCU, nặng hơn nếu cache M-08 bị hoãn) `minimumNumberOfCalls = 10`, cửa sổ breaker đếm theo lời gọi (≈ 2 lời gọi mỗi charge) và trần ≈ 6,5s của charge đồng bộ: hiệu chỉnh theo số liệu Phase 8 (Phase 2 checklist D15, D12, C37) | Q |
| C7 | Retry cap và biến thể paid của adversarial; số lần lặp so sánh mesh; cache lạnh/ấm; cơ chế reset dữ liệu; vị trí script K6; `preAllocatedVUs` | Q |
| C8 | Kiểm tra cột `users` theo DDL cho dữ liệu load test (phần permission của Student đã giải quyết, xem bên dưới) | V |

## D. Tooling và cấu hình

| # | Mục | Loại |
|---|---|---|
| D2 | Nâng Semgrep/Dependency-Check lên blocking: ngưỡng severity và khoảng thời gian ổn định chưa định nghĩa | Q |
| D3 | Gradle task `resilienceTest` cho kịch bản on-demand (kịch bản 3) — vẫn cần quyết, nhưng không còn chặn Phase 4 sign-off: M-40/M-41 (INV-11, INV-10 relay) có đường Passing riêng qua Integration level thường (`integration-testing.md` §4); kịch bản 3 chỉ là bằng chứng bổ sung | Q |
| D5 | Hình dạng IT có ≥2 app instance (mở rộng `IntegrationTestSupport` hay tạo base class anh em) | Q |
| D7 | Bộ rule Semgrep chỉ là giá trị khởi đầu | Q |

## E. Test/INV

| # | Mục | Loại |
|---|---|---|
| E1 | Hệ số so sánh latency probe (resilience §2.1) | Q |
| E3 | Redisson: xử lý kết nối treo có nằm trong nhánh catch của `registration.md` §2 không | V |
| E4 | Giới hạn burst khi kill pod; cơ chế làm pod "unhealthy nhưng còn chạy" | Q |
| E5 | INV-20: giữ ở trạng thái pending hay xóa cho tới khi STRICT bật; phase của INV-19/20 là giả định | Q |
| E6 | Dạng test injection động (A03); OWASP edition (phần S-17 đã giải quyết, xem bên dưới) | Q |

## F. Chưa chạy thử, chưa verify

| # | Mục |
|---|---|
| F1 | Makefile `pre-migration`: lọc `-l`, generator options, vòng retry `pg_isready` |
| F2 | Property/`HealthContributor` cho nhóm `dependencies` (DEGRADED) |
| F3 | Kubelet probe rewrite dưới STRICT |
| F5 | `withReuse(true)` với reuse bật, và chạy test song song |
| F6 | Mesh-off R2 với STRICT |

## G. Cờ có sẵn trong corpus từ trước, không đụng, chưa audit lại

- Endpoint suy luận trong api-contract: `getPayment`, `cancelRegistration`, body của `chargePayment`. (`syncScanBatch`, `listWorkshopAttendances`, `maxItems` 500 đã chốt 2026-10-30, Phase 5 checklist D46, D54; cờ "Inferred" đã gỡ khỏi contract ở task 1.2, 2026-10-30.)
- Chuyển trạng thái `SUSPENDED → ACTIVE` chưa xác nhận (`guidelines/test-design-techniques.md` §2).
- Phase cho INV-13.
- Package-glob của JaCoCo.
- Bộ lọc `jq` của contract-diff.
- Tên database `unihub`.
- Các giá trị khởi đầu chưa qua load test: tài nguyên, connection pool, ngưỡng alert.
- Khoảng ~5% chưa phân bổ trong tỉ lệ pyramid (`strategy/test-strategy.md` §7).

## Đã giải quyết

| # | Mục | Quyết định | Ngày |
|---|---|---|---|
| B4 | Response status khi S3 unreachable | `503` problem `storage-unavailable` ở `confirmBrochureUpload` (Phase 5 checklist D60; `workshop.md` §8, `resilience-testing.md` §2.6); built and asserted by `ObjectStorageOutageIT` (M-51, `Passing`, 2026-11-02) | 2026-10-30 |
| D4 | Mobile test runner; CI job cho test mobile | Jest + `jest-expo` + React Native Testing Library; job CI `mobile` độc lập (typecheck, lint, Jest), không build EAS trong CI (Phase 5 checklist D57); job đã chạy xanh trên `5c22341` | 2026-10-30 |
| D1 | Required status checks trên `main` | Yêu cầu cả năm check: `lint`, `unit-test`, `integration-test`, `contract-diff`, `secret-scan-gitleaks` (`technical-design/platform/deployment-and-cicd.md` §5) | 2026-09-24 |
| E2 | `minimumNumberOfCalls` của breaker | 10, override được trong test; hiệu chỉnh lại ở Phase 8 (`payment.md` §2; Phase 2 checklist D12) | 2026-10-14 |
| E6 (một phần) | Phase sở hữu test chữ ký webhook S-17 | Phase 2, dòng `test-matrix.md` M-79 (Phase 2 checklist D13) | 2026-10-14 |
| C8 (một phần) | Permission của Student | `REGISTRATION_CREATE`, seed bằng `V4` (`database-schema.md` §10.1; Phase 2 checklist D7) | 2026-10-14 |
| F4 | Fixture replica (`pg_basebackup`, `pg_hba`) | Đã chạy thật: `ReplicaIntegrationTestSupport` clone Master bằng `pg_basebackup` qua bridge network mặc định, thêm dòng `host replication` vào `pg_hba.conf` rồi reload; `ReplicaFixtureIT` chứng minh standby nhận schema, pause replay làm replica cũ, resume thì hội tụ (Phase 2 checklist §1.1) | 2026-10-14 |
| D6 | Tạo secret `NVD_API_KEY`; ghim image Semgrep/Dependency-Check/Gitleaks | Secret đã tạo ở Phase 1 (`phase-1-foundation.md` Mục 4). Image ghim đúng phiên bản mà `latest` trỏ tới lúc đó (cùng manifest digest, nên hành vi không đổi): Semgrep `1.181.1`, Dependency-Check `13.0.0`, Gitleaks `v8.30.1`; Gitleaks (job chặn) đã chạy bản ghim trên toàn bộ lịch sử repo, không phát hiện rò rỉ. Ghim image không ghim rule Semgrep hay dữ liệu NVD (`deployment-and-cicd.md` §5; Phase 2 checklist 5) | 2026-10-26 |
