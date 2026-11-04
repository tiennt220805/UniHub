# Phase 6 — Bằng chứng CI/CD (Mục 8: 8.1, 8.2, 8.3)

> Đo ngày 2026-11-04/05. Các con số CI lấy từ GitHub Actions (`gh run view`); các con số "local" từ máy của chủ dự án (Apple Silicon, 8 nhân, JDK Temurin 21.0.11, Docker Desktop).
> **Không có test nào bị `@Disabled`** trong `apps/backend/src` và **không có `TODO`/`FIXME`** trong mã test.

## 8.1 — `integration-test` xanh với các IT mới, và thời gian của job

Job `integration-test` xanh ở mọi commit của Phase 6 từ `8238220` trở đi (hai commit trước đó, `ef0771f` và `74fb9f3`, đỏ vì cổng JaCoCo và đã sửa ở `8238220`). Cả chín job của `ci.yml` xanh trên `8238220`, `e643629`, `2ffee25`, `f559368` và `74e93af`.

| Commit | Nội dung | `integration-test` |
|---|---|---|
| `06a60cd` … `2720357` | Phase 5 (baseline) | 12m55s – 13m55s |
| `a58e814` | Phase 6 Mục 1 | 13m01s |
| `8238220` | Mục 2 và 3 (audit, correlation id) | 12m53s |
| `e643629` | Mục 4 (metric) | **15m53s** |
| `2ffee25` | Mục 5 (tracing) | 16m23s |
| `f559368` | Mục 6 (alert, dashboard, `AuditAlertIT`, `ObservabilityProvisioningIT`) | 16m13s |
| `74e93af` | Mục 7 (M-38) | 16m59s |

**Job chậm đi khoảng 3 phút so với baseline của Phase 5.** Bước nhảy nằm ở Mục 4 (+3 phút), không phải ở Mục 6: hai IT mới của Mục 6 (nặng nhất là `AuditAlertIT`, kéo hai image Prometheus và Grafana) không làm job chậm đáng kể, ít hơn ước tính +1 phút của D72 (thực tế gần 0).

Đo riêng từng lớp trên máy local:

| Lớp | Thời gian |
|---|---|
| `AuditAlertIT` | 50–95 s |
| `AuditOutageIT` | 44–46 s |
| `TracingIT` | 33 s |
| `MetricsExportIT` | 30 s |
| `CorrelationPropagationIT` + `…UnsampledIT` | 17,5 s + 16,5 s |
| `DependencyProbeIT` | 4 s |
| `ObservabilityProvisioningIT` | 1,3 s |
| `ManagementPortIT` | 1 s |

Tổng các lớp đã đo khoảng 4–5 phút, cùng bậc với mức tăng 3 phút, **nhưng bước nhảy giữa các lần chạy không khớp từng lớp**: các lớp của Mục 2–3 (`AuditOutageIT`, hai lớp `CorrelationPropagation*`, khoảng 80 s) không làm job nhúc nhích ở `8238220`, còn Mục 4 chỉ thêm khoảng 35 s theo đo local mà job tăng 3 phút. **Không quy được phần chênh cho một nguyên nhân cụ thể** nếu thiếu số liệu từng lớp từ CI; CI hiện không tải báo cáo test lên (bước `upload-artifact` duy nhất của `ci.yml` chỉ tải báo cáo Dependency-Check). Đề xuất: tải `apps/backend/build/reports/tests` và `build/test-results` lên (`if: always()`) ở `unit-test` và `integration-test`, vừa để quy thời gian, vừa để lần sau một test flaky có log để đọc (M-38). Chưa làm: nằm ngoài ba việc của Mục 8, chờ owner đồng ý.

## 8.2 — Không test nào flaky (10 vòng liên tiếp, local, JDK 21)

Mỗi vòng: `./gradlew cleanIntegrationTest integrationTest --tests '*AuditOutageIT' --tests '*AuditAlertIT' --tests '*CorrelationPropagationIT' --tests '*CorrelationPropagationUnsampledIT' --tests '*TracingIT'`. JDK: `openjdk 21.0.11 (Temurin)`.

**10/10 vòng xanh: 180 lượt test (18 mỗi vòng), 0 lỗi, 0 bị bỏ qua.** Thời gian từng lớp mỗi vòng (giây, lấy từ báo cáo XML của vòng đó):

| Vòng | Cả lệnh Gradle (s) | AuditAlertIT | AuditOutageIT | CorrelationPropagationIT | …UnsampledIT | TracingIT |
|---|---|---|---|---|---|---|
| 1 | 258 | 73.0 | 44.5 | 17.9 | 16.8 | 32.8 |
| 2 | 269 | 86.1 | 44.3 | 17.5 | 16.5 | 32.6 |
| 3 | 242 | 56.7 | 46.1 | 17.7 | 16.5 | 32.6 |
| 4 | 279 | 94.7 | 44.4 | 17.6 | 16.7 | 32.8 |
| 5 | 262 | 76.0 | 45.7 | 17.6 | 16.9 | 32.7 |
| 6 | 261 | 74.2 | 44.6 | 17.7 | 17.0 | 32.8 |
| 7 | 238 | 50.3 | 45.8 | 17.9 | 16.4 | 32.6 |
| 8 | 241 | 55.8 | 44.3 | 17.9 | 16.8 | 32.8 |
| 9 | 269 | 85.1 | 44.5 | 17.7 | 16.9 | 32.5 |
| 10 | 261 | 74.1 | 44.5 | 17.9 | 16.7 | 32.8 |

`AuditAlertIT` là lớp dao động nhiều nhất (50–95 s) vì phải chờ Grafana chu kỳ 20 giây đánh giá rule; nó không thất bại lần nào. Báo cáo XML từng vòng nằm ở thư mục tạm của lần đo (2,7 MB, không đưa vào repo); bảng trên là bản rút từ chúng.

## 8.3 — `contract-diff`, `web`, `mobile`, Gitleaks, và OWASP

- **`contract-diff`, `web`, `mobile`, Gitleaks xanh** trên `f559368` và `74e93af`. Không có mật khẩu Grafana nào trong repo: biến `GF_SECURITY_ADMIN_PASSWORD` chỉ xuất hiện một lần, trong `AuditAlertIT`, với giá trị là một UUID sinh mới mỗi lần chạy; compose không đặt mật khẩu và tài liệu nêu tài khoản `admin`/`admin` mặc định của chính Grafana (kiểm thật ở Grafana 13.2.4: API nhận `admin:admin`). Gitleaks chặn một lần trong Phase 6, ở phía GitHub Push Protection (một khóa ví dụ của tài liệu Stripe nằm trong một unit test); đã đổi thành giá trị không khớp mẫu.
- **Delta OWASP Dependency-Check** (báo cáo của run `41452684364` ở sign-off Phase 5 so với `41856654971`): số phụ thuộc quét được tăng từ 145 lên 166 (21 file jar mới), số phát hiện duy nhất (CVE, mức độ) tăng từ 239 lên 245 (**+6**, không mất phát hiện nào).
  - Phụ thuộc mới đáng kể: `datasource-micrometer` 1.0.6, `datasource-micrometer-spring-boot` 1.0.6, `datasource-proxy` 1.10.1, `micrometer-tracing` và `micrometer-tracing-bridge-otel` 1.3.5, `opentelemetry-api` 1.37.0 (cùng `-incubator`, `-instrumentation-api`, `-semconv`), `okhttp` 4.12.0, `okio-jvm` 3.6.0, và bảy mô-đun `prometheus-metrics-*` 1.2.1 (D80, Q18).
  - **Sáu phát hiện mới đều nằm trên các jar OpenTelemetry Java** (HIGH: CVE-2026-24051, CVE-2026-29181, CVE-2026-39883; MEDIUM: CVE-2026-39882, CVE-2026-41178, CVE-2026-54285). Đọc mô tả của từng CVE: **cả sáu là lỗi của OpenTelemetry-Go (năm cái) hoặc opentelemetry-js (một cái)** — SDK Go thực thi `ioreg`/`kenv` trên macOS/BSD, exporter OTLP Go, baggage propagator Go và JS — không phải của thư viện Java mà dự án dùng. Dependency-Check ghép chúng vào jar Java theo tên "opentelemetry" (trùng CPE). **Đánh giá của tôi: dương tính giả**, không có mã Go hay JS nào trong dự án. Job Dependency-Check không chặn (`continue-on-error`), nên không cần suppression để CI xanh; việc thêm một file suppression có lý do cho sáu CVE này là quyết định của owner.
  - Các phát hiện sẵn có (hai HIGH và một MEDIUM của `httpclient5`/`httpcore5` từ AWS SDK, CVE-2025-7962 của gói mail, nền Spring Boot 3.3.5) **không xử lý** ở Phase 6, đúng theo Q18; chúng vẫn nằm trong báo cáo.
  - **Chuyển sang `phase-6.md` §5** (Mục 10): sáu dương tính giả trên, và ghi rằng OkHttp 4.12.0 (kéo theo Kotlin stdlib) vào ứng dụng do D80.
