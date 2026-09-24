Tạo ngày 2026-09-24 — nguồn tham chiếu duy nhất cho các mục còn mở khi bước vào implementation — cập nhật tại đây khi một mục được giải quyết, không tạo checklist mới ở nơi khác.

# Implementation Readiness Checklist — UniHub

Ký hiệu: **Q** = cần bạn/team quyết; **V** = verify lúc implementation; **D** = còn thiếu thiết kế.

## A. Precondition Phase 7 sign-off (`strategy/test-plan.md` §1)

| # | Mục | Loại |
|---|---|---|
| A1 | P1: alert bù cho Redis/RabbitMQ degraded; chưa có rule hay metric, thuộc `observability.md` §4/§6 | D |
| A2 | P2: ingress-nginx được inject, hoặc PeerAuthentication exception hẹp | V |
| A3 | P3: native Job-termination chạy thật trên Istio 1.24.x + K8s/Minikube (và có phải bật thủ công không); ảnh hưởng cả deploy PERMISSIVE hiện tại | V |
| A4 | P4: dựng stack Prometheus/Grafana/Tempo rồi inject hoặc scoped exception (manifest chưa tồn tại) | V/D |

## B. Thiết kế còn thiếu

| # | Mục | Loại |
|---|---|---|
| B1 | 1c: health-check điều khiển fallback Replica-unreachable → Master (hướng đã duyệt, cơ chế chưa có; `overlays/dev` 0 replica giải quyết theo) | D |
| B2 | INV-07 với Redis outage dài hơn retry chain (~5m35s): hold-expiry bị đưa vào parking lot | Q |
| B3 | Tín hiệu/metric cho audit-publish failure (nửa sau INV-14 hiện chưa test được) | D |
| B4 | Response status khi S3 unreachable (contract chỉ có 404/409) | Q |
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
| C7 | Retry cap và biến thể paid của adversarial; số lần lặp so sánh mesh; cache lạnh/ấm; cơ chế reset dữ liệu; vị trí script K6; `preAllocatedVUs` | Q |
| C8 | Permission codes trong JWT Student: seed SQL cho Student là rỗng nhưng file migration chưa tồn tại; kiểm tra cột `users` theo DDL | Q/V |

## D. Tooling và cấu hình

| # | Mục | Loại |
|---|---|---|
| D2 | Nâng Semgrep/Dependency-Check lên blocking: ngưỡng severity và khoảng thời gian ổn định chưa định nghĩa | Q |
| D3 | Gradle task `resilienceTest` cho kịch bản on-demand (kịch bản 3) | Q |
| D4 | Mobile test runner; CI job cho test mobile chưa có | Q |
| D5 | Hình dạng IT có ≥2 app instance (mở rộng `IntegrationTestSupport` hay tạo base class anh em) | Q |
| D6 | Tạo secret `NVD_API_KEY`; ghim image Semgrep/Dependency-Check/Gitleaks (hiện là `latest`) | V |
| D7 | Bộ rule Semgrep chỉ là giá trị khởi đầu | Q |

## E. Test/INV

| # | Mục | Loại |
|---|---|---|
| E1 | Hệ số so sánh latency probe (resilience §2.1) | Q |
| E2 | `minimumNumberOfCalls` của breaker | Q |
| E3 | Redisson: xử lý kết nối treo có nằm trong nhánh catch của `registration.md` §2 không | V |
| E4 | Giới hạn burst khi kill pod; cơ chế làm pod "unhealthy nhưng còn chạy" | Q |
| E5 | INV-20: giữ ở trạng thái pending hay xóa cho tới khi STRICT bật; phase của INV-19/20 là giả định | Q |
| E6 | Chủ sở hữu phase cho test webhook signature (S-17); dạng test injection động (A03); OWASP edition | Q |

## F. Chưa chạy thử, chưa verify

| # | Mục |
|---|---|
| F1 | Makefile `pre-migration`: lọc `-l`, generator options, vòng retry `pg_isready` |
| F2 | Property/`HealthContributor` cho nhóm `dependencies` (DEGRADED) |
| F3 | Kubelet probe rewrite dưới STRICT |
| F4 | Fixture replica (`pg_basebackup`, `pg_hba`) |
| F5 | `withReuse(true)` với reuse bật, và chạy test song song |
| F6 | Mesh-off R2 với STRICT |

## G. Cờ có sẵn trong corpus từ trước, không đụng, chưa audit lại

- Endpoint suy luận trong api-contract: `syncScanBatch`, `listWorkshopAttendances`, `getPayment`, `cancelRegistration`, body của `chargePayment`, `maxItems` 500.
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
| D1 | Required status checks trên `main` | Yêu cầu cả năm check: `lint`, `unit-test`, `integration-test`, `contract-diff`, `secret-scan-gitleaks` (`technical-design/platform/deployment-and-cicd.md` §5) | 2026-09-24 |
