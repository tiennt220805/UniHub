# Phase 7 — Bằng chứng CI/CD (Mục 11: 11.1, 11.2, 11.3)

> Đo ngày 2026-11-05 … 2026-11-08. Số CI lấy từ GitHub Actions (`gh run view`, artifact `integration-test-reports` và `dependency-check-report`); số "local" từ máy của chủ dự án (Apple Silicon, 8 nhân, JDK Temurin 21.0.11, Docker Desktop 27.4.0), do Claude Code chạy theo yêu cầu "triển khai tiếp mục 11".
> **Không có test nào bị `@Disabled`/`@Ignore`** trong `apps/backend/src` (0 lần xuất hiện), **không có `TODO`/`FIXME`** trong mã test, **không có file test nào bị xóa** và **không có phương thức `@Test`/`@ParameterizedTest` nào bị xóa** trong `74e93af..HEAD` (111 phương thức thêm mới).

## 11.1 — Mười job xanh trên một SHA, và thời gian `integration-test`

**Mười job** của `ci.yml` (`lint`, `unit-test`, `integration-test`, `contract-diff`, `web`, `mobile`, `secret-scan-gitleaks`, `sast-semgrep`, `dependency-check`, `build-and-push`) đều `success` trên: `f648fd0` (run 42388031802), `308e3de` (run 42394159591) và **`39198c4` (run 42396804500)**, SHA mới nhất lúc ghi. Trên `39198c4` cả mười job `success`, `integration-test` 17m59s, `build-and-push` 0m37s. Job `build-and-push` đẩy hai image đa kiến trúc (`amd64`, `arm64`) với tag là SHA đầy đủ; `f648fd0` là SHA mà Mục 10 đã deploy và cluster kéo đúng digest đó.

Số test trên CI (artifact `integration-test-reports`, run 42394159591): **145 lớp, 879 test, 0 lỗi, 0 bị bỏ qua** (Phase 6: 836 test).

Thời gian bước `Run ./gradlew integrationTest --no-daemon` (cả job `integration-test` chỉ có một bước dài):

| Commit | Nội dung | `integration-test` |
|---|---|---|
| `74e93af` | cuối Phase 6 (baseline) | 16m59s (bước 16m42s) |
| `f8e7a3f` | Mục 2 (không đổi test) | 18m26s |
| `fe66f9b` | Mục 3: bộ giới hạn tốc độ (INV-17) | 18m45s (bước 18m33s) |
| `0aa6ba8` | (sửa fixture đua) | 17m39s |
| `1d689d8` | Mục 4: `MigrationCompatibilityIT` | 18m05s (bước 17m54s) |
| `1cf9499` | Mục 5: manifest Kubernetes | 21m42s |
| `52d27b3` … `91fe9db` | Mục 6, 7 | 17m33s – 20m06s |
| `f648fd0`, `308e3de`, `39198c4` | Mục 10–11 (cùng mã test) | 19m32s, 20m13s, 17m59s |

**Mười lăm run xanh có đủ mười job của Phase 7: trung bình 19m16s, thấp nhất 17m33s, cao nhất 21m42s; baseline 16m59s, tức chậm đi khoảng 2–3 phút (khoảng 14% đến 28%).** Ba commit cuối có **cùng mã test** (chỉ khác tài liệu và script) mà chạy 19m32s, 20m13s và 17m59s: độ lệch giữa các lần chạy cùng mã là khoảng 2 phút, bằng cỡ mức tăng, nên **phần lớn mức tăng không thể quy cho một thay đổi cụ thể** (máy chủ chạy CI không ổn định).

Phần quy được, theo thời gian từng lớp trên CI (run 42394159591):

| Lớp (Phase 7) | Test | Thời gian trên CI |
|---|---|---|
| `MigrationCompatibilityIT` | 3 | 58 s |
| `RateLimitIT` | 14 | 8 s |
| `RateLimitOutageIT` | 2 | 6 s |
| `RateLimitMultiInstanceIT` | 3 | 1 s |
| `RateLimitForwardedHeadersIT` | 1 | < 1 s |
| `StatelessInstancesIT` | 2 | < 1 s |
| **Tổng** | 25 | **khoảng 73 s** |

Các lớp của Phase 7 chỉ góp khoảng **1,2 phút** cho job; `MigrationCompatibilityIT` chiếm gần hết vì nó dựng jar của bản trước từ lịch sử git và chạy hai JVM con (Mục 4 đo `MigrationCompatibilityIT` 56 s và job +26 s ở `1d689d8`; trên CI hôm nay là 58 s, cùng mức). Ba lớp chậm nhất của cả bộ vẫn là của Phase 5–6: `AuditAlertIT` 122 s, `ObjectStorageOutageIT` 117 s.

## 11.2 — Ba IT của Phase 7: 10 vòng liên tiếp xanh ở local

Mỗi vòng: `JAVA_HOME=…/temurin-21.jdk ./gradlew cleanIntegrationTest integrationTest --no-daemon --tests '*RateLimitMultiInstanceIT' --tests '*StatelessInstancesIT' --tests '*MigrationCompatibilityIT'`. `cleanIntegrationTest` để Gradle không báo "UP-TO-DATE"; `buildPreviousRelease` dựng lại jar N-1 mỗi vòng. Cluster Minikube được dừng (`minikube stop -p unihub`, bật lại bằng `minikube start -p unihub`) để nhường bộ nhớ cho Testcontainers, vì host đang dùng gần hết swap. Nhật ký: `phase-7-ci-checks/11-2-ten-rounds.txt`.

| Vòng | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
|---|---|---|---|---|---|---|---|---|---|---|
| Kết quả | xanh | xanh | xanh | xanh | xanh | xanh | xanh | xanh | xanh | xanh |
| Thời gian cả vòng | 101 s | 98 s | 98 s | 97 s | 93 s | 90 s | 91 s | 90 s | 91 s | 91 s |
| `MigrationCompatibilityIT` | 47 s | 49 s | 49 s | 46 s | 45 s | 46 s | 46 s | 45 s | 45 s | 46 s |

Mỗi vòng chạy 8 test (3 + 3 + 2), **không lỗi, không bỏ qua; 80 lần chạy test, 0 lần đỏ**. `RateLimitMultiInstanceIT` 2 s và `StatelessInstancesIT` dưới 1 s mỗi vòng. Đo bằng tiến trình thật chứ không dựa vào mô phỏng: `RateLimitMultiInstanceIT` và `StatelessInstancesIT` dựng hai instance, `MigrationCompatibilityIT` chạy JVM con.

## 11.3 — `contract-diff`, `web`, `mobile`, Gitleaks và OWASP

- **`contract-diff`, `web`, `mobile`, `secret-scan-gitleaks`: `success`** trên `f648fd0`, `308e3de` và `39198c4`. Gitleaks đã đỏ một lần ở `d509fda` (một dương tính giả trong lịch sử, `k8s/scripts/kill-pod.sh:86`, `curl -u` với Secret đọc lúc chạy); chủ dự án duyệt một mục `.gitleaksignore` ngày 2026-11-06 (`8e5e04a`) và từ đó Gitleaks xanh.
- **Delta OWASP Dependency-Check** (báo cáo của run `41865133427` ở cuối Phase 6, ngày 2026-11-04, so với run `42394159591`, ngày 2026-11-07; cùng bản công cụ 13.0.0, NVD cập nhật ba ngày): số phụ thuộc quét được **166 → 166**, số phát hiện duy nhất (CVE, mức độ) **245 → 245**, **0 thêm, 0 mất**. Phase 7 không thêm phụ thuộc chạy nào vào backend, web hay mobile (chỉ thêm tác vụ Gradle `buildPreviousRelease` và `tests/e2e/package.json`, Playwright, không xuất hiện trong phát hiện nào), và ba hình ảnh/manifest mới không nằm trong phạm vi quét (OWASP quét jar và manifest npm, không quét image).
- **Nền cũ còn nguyên, nói thẳng để chủ dự án quyết:** trong 245 phát hiện có 37 `CRITICAL` và 108 `HIGH` (đếm theo cặp CVE–jar là 306), tập trung ở các phiên bản **Spring Boot 3.3.5, spring-core 6.1.14, spring-security 6.3.4, `tomcat-embed-core` 10.1.31, `netty-transport` 4.1.114.Final, `jackson-databind` 2.17.2**, cộng một số gói npm (handlebars, vite, undici…). Job `dependency-check` có `continue-on-error: true` nên không chặn CI. Đây là tuổi của các phụ thuộc đã có từ trước Phase 7, **không phải do Phase 7 gây ra**, nên Phase 7 không xử lý; nâng Spring Boot là một quyết định riêng. Sáu phát hiện OpenTelemetry mà sign-off Phase 6 xét là dương tính giả (CVE của OpenTelemetry-Go/JS khớp tên với jar Java) vẫn nằm trong số này.

## Việc cần chủ dự án quyết hoặc ghi vào `phase-7.md` §5

- Mức tăng thời gian `integration-test`: trung bình +2–3 phút so với baseline Phase 6, trong đó các lớp Phase 7 chỉ chiếm khoảng 73 s; phần còn lại là độ lệch của máy chạy CI.
- Có nâng Spring Boot (và các thư viện kéo theo) để giảm 37 `CRITICAL` / 108 `HIGH` ở nền OWASP hay không.
- Cluster Minikube đang **dừng** (để chạy 11.2); bật lại bằng `minikube start -p unihub` nếu cần.
