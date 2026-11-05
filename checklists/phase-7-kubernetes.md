# Phase 7 — Kubernetes Deployment: Checklist Triển Khai

> **Mục đích file này:** checklist *thực thi* cho Phase 7, cùng vai trò với `phase-1-foundation.md` … `phase-6-observability.md`. Nó không thay thế `docs/testing/strategy/test-plan.md` §1 (nguồn thẩm quyền cho exit criteria và bảng P1–P4), `docs/testing/reports/test-matrix.md` (trạng thái từng test) hay `docs/testing/reports/release-sign-offs/phase-template.md` (sign-off). File này chỉ **tổng hợp và sắp thứ tự công việc**, trỏ vào 3 file đó làm bằng chứng. Nơi có mâu thuẫn, 3 file kia luôn đúng.
>
> **Cách dùng:** tick `[x]` khi xong thật, không tick trước. Mỗi mục nên có commit tương ứng. Cập nhật **Trạng thái tổng thể** sau mỗi buổi làm việc.

| Trường | Giá trị |
|---|---|
| Phase | 7 — Kubernetes Deployment |
| Ngày bắt đầu | 2026-11-05 (checklist soạn cùng ngày, sau khi ký `phase-6.md`) |
| Trạng thái tổng thể | `☑ Chưa bắt đầu` / `☐ Đang làm` / `☐ Chờ sign-off` / `☐ Hoàn tất` — **Mục 0 chốt 2026-11-05 (Q1–Q24 → D81–D104; owner giao Claude Code + advisor chốt thay: "Bạn hãy thảo luận với advisor để chốt các vấn đề trên cho tôi"; owner xác nhận sau). Ô của Mục 0 chưa tick: chỉ tick khi tài liệu sở hữu đã được sửa, việc đó chờ owner đồng ý (như Phase 6). Việc owner phải làm trước khi Mục 2 và Mục 5 chạy được: tạo secret Docker Hub (Q4), nâng bộ nhớ Docker Desktop (Q2). Việc tiếp theo: sửa tài liệu sở hữu và đóng Mục 0B (C45–C62), rồi Mục 1.** |
| Nhánh làm việc | `main` (push thẳng, không PR — `test-matrix.md` §4.4) |
| INV trong scope | INV-15 (migration đọc được bởi cả pod cũ lẫn pod mới khi đang rollout), INV-16 (pod hỏng tự khởi động lại; traffic chỉ tới pod Ready), INV-17 (rate limit dựa trên trạng thái chung toàn cluster), INV-20 (mTLS STRICT: không chấp nhận hop plaintext nào). Gián tiếp: INV-05, INV-08 (nửa Kubernetes của kịch bản 1 và 2: M-64, M-65), INV-18 (Gitleaks gate `build-and-push`) |
| Matrix rows | M-58 … M-70 (`test-matrix.md` §4.1: "7 — M-58 … M-70 (13 rows)"); dòng `⚠`: M-60, M-67, M-68. Cộng các mục hoãn có đích Phase 7: CSP bằng HTTP header (`test-matrix.md` §4.5; `phase-2.md` §5 dòng 7), rate limiter fail-open (`phase-3.md` §5 dòng 3), và `phase-6.md` §5 các dòng 2 (crash thấy được như một lần restart), 3 (Ingress gắn correlation ID), 11 (manifest stack observability), 12 (P1 có hiệu lực) |
| Tiền điều kiện sign-off | P1–P4 của `test-plan.md` §1 (**cả bốn hiện `NOT MET`**). P1 chặn sign-off; P2–P4 chặn việc chuyển STRICT, và INV-20 cần STRICT |
| Tham chiếu | `requirement.md` §2.2, §2.3, §2.5, §2.11, §3.3 · `proposal.md` §14–§16 · `architecture.md` §1.2, §4.2, §5.4, §6.2 · `test-plan.md` §1 (dòng Phase 7, bảng P1–P4), §2 · `test-strategy.md` §2 (INV-15/16/17/20), §4 · `deployment-and-cicd.md` (toàn bộ: ADR, §1–§7) · `distributed-coordination.md` §1, §3, §4 · `observability.md` §5, §6, §8 · `resilience-testing.md` §2.2, §2.3, §2.8 · `security-testing.md` §2.2 (S-13…S-16, S-19) · `e2e-testing.md` §2–§3 · `integration-testing.md` §2 · `mocking-guidelines.md` §1 · `web-coding-standards.md` §11 · `backend-coding-standards.md` §27 · `phase-6.md` §5 |

### Ký hiệu nhãn

Giữ nguyên quy ước của các phase trước: **`[M-61]`** đậm = task là bằng chứng của dòng; `[→ M-61]` = tiền đề; `[M-71 · P8]` = dòng của phase khác, chỉ chuẩn bị; `[§4.5]` = đóng một mục hoãn ở `test-matrix.md` §4.5; `[P1]` … `[P4]` = tiền điều kiện của `test-plan.md` §1; `[— không dòng]` = không có dòng matrix (ghi lý do).

**Bảng tra ngược (dòng → task):** *(sửa sau khi chốt Mục 0, 2026-11-05)*

| Dòng | INV | Task chứng minh | Task tiền đề |
|---|---|---|---|
| M-58 | INV-15 | 4.3 (per-push), 8.4 (diễn tập rollout) | Q8 · 4.1–4.2, 5.x |
| M-59 | INV-16 | 8.1, 8.2 | Q2, Q9 · 5.x, 7.x |
| M-60 | — | 5.7 | Q6 · 5.3 |
| M-61 | INV-17 | 3.5 | Q10, Q12 · 3.1–3.4 |
| M-62 | INV-17 | 8.3 | Q3, Q11 · 3.x, 5.x |
| M-63 | INV-20 | 7.6 | Q13–Q16 · 7.1–7.5 |
| M-64 | INV-05 | 8.5 | Q6 · 5.x |
| M-65 | INV-08 | 8.6 | Q19 · 5.x, 6.x |
| M-66 | — | 2.3 | Q4, Q23 · 1.x, 2.1–2.2 |
| M-67 | — | 9.4 | Q17 · 5.6 |
| M-68 | — | 3.6 | Q12 |
| M-69 | — | 9.2 | Q5, Q6, Q20 · 5.x, 5.8, 9.1 |
| M-70 | — | 9.3 | Q6, Q20 · 5.8, 9.1 |
| CSP header (§4.5) | — | 1.3, 9.4 | Q5 |
| Rate limiter fail-open (`phase-3.md` §5) | INV-08 (gián tiếp) | 3.4 | Q10 |
| P1 | — | 6.4 | Q19 · 6.1–6.3 |
| P2 | — | 7.2 | Q3, Q13 |
| P3 | — | 7.3 | Q1, Q14 |
| P4 | — | 7.4 | Q15 |

---

## Ranh giới Phase 7 ↔ các phase khác (đọc trước khi làm)

Hiện trạng đã kiểm 2026-11-05:

- **Chưa có gì của Phase 7 trong repo:** không `Dockerfile`, không thư mục `k8s/`, không `Makefile`, không job `build-and-push` trong `.github/workflows/ci.yml` (chín job hiện có: `lint`, `unit-test`, `integration-test`, `web`, `mobile`, `contract-diff`, `sast-semgrep`, `dependency-check`, `secret-scan-gitleaks`). Secret GitHub chỉ có `NVD_API_KEY`; **`DOCKERHUB_USERNAME`/`DOCKERHUB_TOKEN` chưa có** (`gh secret list`).
- **Không có rate limiter.** `RegistrationController`, `AuthController`, `WorkshopController` chỉ *khai báo* `429` + `Retry-After` cho `contract-diff`, có comment nói rõ "documented only … no rate limiter (INV-17)". Redisson `3.45.1` có `RRateLimiter.trySetRate(RateType, long, Duration, Duration keepAlive)`, nên key theo IP có thể tự hết hạn.
- **Có sẵn khung test hai instance:** `MultiInstanceHarness` (`src/integrationTest/.../shared/infrastructure/`; D22 của Phase 3, `NotificationRedeliveryIT` của M-40) — hai Spring context trên cổng ngẫu nhiên, mỗi context một `RedissonClient`. TODO "extend `IntegrationTestSupport` or add a sibling base class" của `security-testing.md` §2.2 đã lỗi thời (C55).
- **Có sẵn cơ chế replica:** `ReplicaIntegrationTestSupport` dựng standby bằng `pg_basebackup --write-recovery-conf` trên `postgres:16-alpine`. M-60 nên dùng cùng cơ chế (`deployment-and-cicd.md` §6: "same images").
- **Migration:** `V1`, `V1_1`, `V1_2`, `V2` … `V8` (10 file); plugin Flyway Gradle `13.8.0`, trong khi Job mẫu dùng `flyway/flyway:10-alpine` (tag trôi).
- **Biến môi trường thật** (`apps/backend/env.example`): `UNIHUB_SECURITY_JWT_SECRET`, `UNIHUB_REDIS_ADDRESS`/`_PASSWORD`, `UNIHUB_NOTIFICATION_SMTP_*`, `UNIHUB_TICKET_SIGNING_KEY`, `UNIHUB_OBJECT_STORAGE_*` (gồm `_CREATE_BUCKET`), `UNIHUB_AI_SUMMARIZATION_*`, `UNIHUB_DEV_SEED_STUDENT_PASSWORD`, `MANAGEMENT_SERVER_PORT`, `MANAGEMENT_OTLP_TRACING_ENDPOINT`, cộng `UNIHUB_PAYMENT_STRIPE_WEBHOOK_SECRET` (`application.yml`). ConfigMap/Secret mẫu ở `deployment-and-cicd.md` §2 dùng tên khác hẳn (C46).
- **Web không có cách deploy:** `apps/web` build ra `dist/` tĩnh, `VITE_API_BASE_URL` mặc định `''` (cùng origin), `VITE_PAYMENT_TEST_MODE` bật dropdown token test của Stripe. CSP hiện chỉ là thẻ `<meta>`. Topology `deployment-and-cicd.md` §2 không có Web, Mailpit, stub Stripe/AI hay stack observability.
- **Máy của owner:** Apple Silicon (`arm64`), 16 GB RAM, 8 CPU; Docker Desktop đang cấp khoảng 7.6 GB. Đã cài `minikube` v1.38.1 (mặc định K8s **v1.35.1**) và `kubectl` v1.36.2 (Kustomize v5.8.1 có sẵn trong đó). **Chưa có** `istioctl`, binary `kustomize` riêng, `helm`. Chưa có cluster minikube nào.
- **Hai sự thật bên ngoài làm lệch blueprint** (tra cứu 2026-11-05, phải kiểm lại khi cài): (1) **ingress-nginx đã ngừng bảo trì từ tháng 3/2026** (Kubernetes SIG Network, blog 2025-11-11: không còn bản phát hành hay bản vá bảo mật; khuyên chuyển sang Gateway API hoặc controller khác); (2) **Istio 1.24.x không hỗ trợ K8s 1.35** — theo ma trận của Solo.io, Istio 1.29 hỗ trợ K8s 1.30–1.35 và 1.28 dừng ở 1.34; phải đối chiếu với trang supported-releases chính thức của Istio.

| Thuộc Phase 7 | Không làm ở đây |
|---|---|
| Dockerfile backend + image Web, CI stage 5 `build-and-push` (M-66) | Phát hành lên App Store/Play Store; build EAS cho `apps/mobile` |
| Manifest Kustomize (`base` + `overlays/dev` + `overlays/loadtest`), `Makefile`, Job Flyway, Secret tạo tay | Triển khai cloud thật (`proposal.md` §5.2) |
| Rate limiter theo người dùng và theo IP trên Redisson (INV-17: M-61, M-62), có M-68 | HPA, autoscaling (ADR Resource Profile: tùy chọn, không bắt buộc) |
| Bằng chứng tương thích migration (INV-15: M-58) | Tối ưu hiệu năng và 12 000 CCU: Phase 8 (M-71 … M-74). Phase 7 chỉ ghi nhận máy 16 GB có chạy nổi hay không |
| Kill-pod, probe, nửa K8s của kịch bản 1 và 2 (INV-16, M-59, M-64, M-65) | Đổi quyết định khóa ghế, idempotency, outbox, audit — các INV trước chỉ được chạy lại, không mở lại |
| Istio, P2–P4, chuyển `PeerAuthentication` sang STRICT, S-19 (INV-20: M-63) | Endpoint đọc audit, use case đổi role/permission (`phase-6.md` §5 dòng 2) |
| Stack observability trong cluster, P1 có hiệu lực, correlation ID gắn ở Ingress | Contact point cho alert nếu owner không chọn (Q19) |
| Smoke test + toàn bộ Playwright trên một lần deploy Minikube mới (M-69, M-70), HTTPS (M-67), CSP bằng header | Check-in Client trên điện thoại gọi vào cluster qua HTTPS tự ký (ghi thành gap, không làm) |

> ⚠ Bốn INV của phase này là **bất biến không được nới** (`CLAUDE.md`). Đặc biệt: (1) INV-17 không được thực thi bằng limiter trong bộ nhớ của từng pod; (2) INV-20 **không được báo `Passing`** khi mesh còn PERMISSIVE, và một "ngoại lệ hẹp" của `PeerAuthentication` phải được ghi rõ là ngoại lệ, không được trình bày như STRICT; (3) INV-15: Job Flyway chạy **trước** Deployment, không bao giờ chạy Flyway lúc ứng dụng khởi động. Nếu một phương án có vẻ cần nới một trong số đó, dừng lại và nêu ra.

---

## 0. Quyết định phải chốt trước khi code (blockers)

Theo `CLAUDE.md`: task phụ thuộc mục còn mở thì **dừng lại hỏi**, không tự đoán. Mỗi mục có phương án + **đề xuất** của Claude Code (đã bàn với advisor). Đánh số tiếp từ D81 để không trùng D1–D80; Q1–Q24 → D81–D104. Chỉ tick khi quyết định đã được **ghi vào tài liệu sở hữu** (cột →).

### 0.1 Nền tảng cluster, tài nguyên, image

- [ ] `[→ P3, M-63, mọi task cluster]` **Q1 — Phiên bản Kubernetes/Minikube/Istio.** Blueprint chốt Istio 1.24.x "trên giấy" và tự ghi là chưa kiểm (`deployment-and-cicd.md` ADR Mesh). Minikube 1.38.1 mặc định K8s 1.35.1, ngoài dải 1.24 hỗ trợ.
  - **✅ Chốt 2026-11-05 (D81) — owner giao Claude Code + advisor chốt thay, owner xác nhận sau:** **Minikube K8s 1.35.1 (mặc định) + Istio 1.31.x** (bản vá mới nhất đã phát hành ≥ 2 tuần), **không phải 1.29 như bản nháp**: trang chính thức `istio.io/latest/about/supported-releases` (đọc 2026-11-05) ghi 1.29 hết hỗ trợ 2026-10-15; 1.30 hỗ trợ K8s 1.32–1.36 (hết khoảng 12/2026), 1.31 hỗ trợ K8s 1.32–1.37 (phát hành 2026-08-27, hết khoảng 02/2027). Cài bằng `istioctl` ghim phiên bản, driver `docker`. **Điều kiện dừng:** nếu `istioctl x precheck` ở task 5.1 báo lỗi, hoặc sidecar gốc không dùng được (Q14), dừng lại và đưa owner — không tự đổi phiên bản K8s.
  - (a) Minikube K8s 1.35.1 (mặc định) + Istio 1.29.x (bản nháp, theo ma trận Solo.io) — **bị loại khi đối chiếu trang chính thức: 1.29 đã hết hỗ trợ**; xem dòng chốt ở trên (1.31.x).
  - (b) Ghim minikube `--kubernetes-version=v1.31.x` để giữ Istio 1.24 — bản Istio đã hết hỗ trợ, không còn bản vá.
  - Kiểm cùng lúc (cho P3): K8s 1.35 có sidecar container gốc; với bản Istio đã chọn, `ENABLE_NATIVE_SIDECARS` bật mặc định hay phải bật bằng `values.pilot.env`.
  - **Đề xuất: (a).** → `deployment-and-cicd.md` ADR Mesh (dòng phiên bản), §7; `test-plan.md` P3
- [ ] `[→ M-59, M-62, Mục 5–9]` **Q2 — Ngân sách tài nguyên và overlay.** `overlays/loadtest` (3 pod API × 768 Mi–1 Gi, Postgres Master + Replica × 2–4 Gi) cộng Istio (istiod + một sidecar mỗi pod), cộng Prometheus/Grafana/Tempo, cộng Web/Mailpit/MinIO/stub không vừa 7.6 GB của Docker. INV-16 và S-15 cần ≥ 2 pod API (`resilience-testing.md` §2.8 đòi `overlays/loadtest`).
  - **✅ Chốt 2026-11-05 (D82) — owner giao Claude Code + advisor chốt thay, owner xác nhận sau:** (a). **Owner phải làm trước Mục 5:** nâng bộ nhớ Docker Desktop lên khoảng 12 GB. Số tài nguyên mới của `overlays/loadtest` chốt khi đo ở 5.7 và ghi thành sai lệch có tên của ADR Resource Profile. **Điều kiện dừng:** nếu `loadtest` + Istio + observability vẫn bị `OOMKilled` ở mức đó, đưa owner — **không bao giờ** lặng lẽ bỏ Istio hay stack observability cho vừa.
  - (a) Nâng Docker Desktop lên khoảng 12 GB, `minikube start --memory=10g --cpus=6`; **giảm** số tài nguyên của `overlays/loadtest` (vd. API 300m/512Mi × 3, Postgres 500m/1Gi) và ghi thành sai lệch của ADR Resource Profile; số thật đo được ghi vào báo cáo.
  - (b) Thêm overlay thứ ba `overlays/ha` (2 pod API, có replica, tài nguyên nhỏ) cho bằng chứng Phase 7; giữ nguyên `loadtest` cho Phase 8.
  - (c) Giữ số của tài liệu, chạy trên một máy lớn hơn.
  - Ghi chú cho Phase 8 (không thuộc scope): 12 000 CCU trên máy 16 GB gần như chắc chắn không đạt; nêu ở `phase-7.md` §5 để owner biết sớm.
  - **Đề xuất: (a)** — không thêm overlay thứ ba (ADR chủ trương đúng hai profile), chỉ đổi số và ghi lý do. → `deployment-and-cicd.md` ADR Resource Profile, §3; `resilience-testing.md` §2.8
- [ ] `[→ P2, M-62, M-67, Q18]` **Q3 — Ingress controller.** `architecture.md` §1.2 và blueprint dùng ingress-nginx (addon của minikube), nhưng dự án này **đã ngừng bảo trì** từ tháng 3/2026. Blueprint cũng chưa giải được P2 (ingress-nginx nằm ngoài mesh).
  - **✅ Chốt 2026-11-05 (D83) — owner giao Claude Code + advisor chốt thay, owner xác nhận sau:** **(b) Istio ingress gateway**, cấu hình bằng `Gateway`/`VirtualService` của Istio (không cần cài CRD Gateway API). Vì thay một Core Component có tên trong `architecture.md` §1.2/§5.4, đây là **điều kiện có tên ở sign-off** (như B3 của Phase 6): owner xác nhận hoặc đổi lại khi ký `phase-7.md`.
  - (a) Vẫn dùng ingress-nginx của minikube; P2 giải bằng cách inject namespace `ingress-nginx` (cần annotation riêng cho controller) hoặc một `PeerAuthentication` ngoại lệ hẹp. Một thành phần không còn bản vá bảo mật đứng ở cổng vào duy nhất.
  - (b) **Istio ingress gateway** (cài cùng Istio), cấu hình bằng Gateway API (`Gateway` + `HTTPRoute`) hoặc `Gateway`/`VirtualService` của Istio. Gateway là một Envoy trong mesh nên **P2 đạt ngay từ thiết kế**, không có ngoại lệ; không thêm thành phần mới ngoài Istio vốn đã có trong stack. Đổi chữ "Ingress Controller (nginx)" ở `architecture.md` §1.2/§5.4 và `ingress.yaml` của blueprint.
  - (c) Controller khác (Traefik, …): thêm một thành phần mới, vẫn phải giải P2.
  - **Đề xuất: (b)** — nhưng nó sửa `architecture.md`, nên là quyết định của owner. → `architecture.md` §1.2, §5.4; `deployment-and-cicd.md` §2, ADR Mesh (a); `test-plan.md` P2
- [ ] `[→ M-66, Mục 5]` **Q4 — Kiến trúc image, registry, cách cluster lấy image.** CI chạy `amd64`, cluster chạy `arm64`. Docker Hub namespace `unihub/` trong blueprint gần như chắc chắn không phải namespace của owner.
  - **✅ Chốt 2026-11-05 (D84) — owner giao Claude Code + advisor chốt thay, owner xác nhận sau:** (a). **Owner phải làm trước Mục 2:** chọn namespace Docker Hub, tạo access token, `gh secret set DOCKERHUB_USERNAME` / `DOCKERHUB_TOKEN`, báo namespace. Chưa có thì Mục 1, 3, 4 vẫn làm được; Mục 2 và M-66 chờ.
  - (a) **Multi-arch** `linux/amd64,linux/arm64` bằng buildx; stage builder đặt `--platform=$BUILDPLATFORM` để Gradle chạy native (không qua QEMU), chỉ stage runtime theo từng kiến trúc. Cluster kéo image theo tag `<git-sha>` từ Docker Hub. Khi làm việc hằng ngày cho phép `minikube image build` với tag `dev-<sha>` (không bao giờ `latest`).
  - (b) Chỉ `amd64` trên Docker Hub (chứng minh M-66); cluster luôn dùng `minikube image load` image build tại máy. Image chạy trong cluster không phải image CI đã kiểm.
  - Owner phải làm: chọn namespace Docker Hub, tạo access token, `gh secret set DOCKERHUB_USERNAME` / `DOCKERHUB_TOKEN`. **Không có hai secret này thì M-66 không thể `Passing`.**
  - **Đề xuất: (a).** → `deployment-and-cicd.md` §1 (bảng tag), §5, §7
- [ ] `[→ M-69, M-70, M-67, §4.5]` **Q5 — Web Application trong cluster.** Playwright cần một Web đã deploy; topology chưa có.
  - **✅ Chốt 2026-11-05 (D85) — owner giao Claude Code + advisor chốt thay, owner xác nhận sau:** (a).
  - (a) **Image Web riêng**: build Vite trong stage Node, phục vụ bằng `nginx-unprivileged` (không root); **cùng origin** với API (gateway route `/api/**` → `backend-api`, `/` → `web`), nên không cần CORS và `VITE_API_BASE_URL` để trống. nginx đặt **CSP bằng HTTP header** (gồm `frame-ancestors`), đóng mục hoãn của Phase 2. Image cho cluster build với `VITE_PAYMENT_TEST_MODE=true` vì cổng thanh toán trong cluster là stub (Q6); ghi rõ ở §5 rằng image "production" không có dropdown này.
  - (b) Phục vụ Web từ chính backend (`static/`): trộn hai deployable, đi ngược cách tách `apps/web`.
  - CI: `build-and-push` build cả hai image, thêm `needs: web` (Q23).
  - **Đề xuất: (a).** → `deployment-and-cicd.md` §1, §2, §5; `web-coding-standards.md` §11; `auth-and-identity.md` §7
- [ ] `[→ M-60, M-64, M-69]` **Q6 — Phụ thuộc trong cluster mà blueprint chưa có.**
  - **✅ Chốt 2026-11-05 (D86) — owner giao Claude Code + advisor chốt thay, owner xác nhận sau:** như đề xuất, và hai điểm phải kiểm đã kiểm: (i) `ChargePaymentSteps` chuyển payment sang `SUCCEEDED` ngay từ phản hồi confirm đồng bộ, nên không cần webhook. Mapping của stub WireMock Stripe lấy theo những gì `StripePaymentGatewayIT` và các IT refund đã stub (tạo PaymentIntent, confirm, retrieve, cancel, list/create refund — không viết danh sách theo trí nhớ); retrieve (sweep reconciliation) trả **cùng** trạng thái với confirm. Đã kiểm: `StripeProperties` có `apiBase` cấu hình được, nên trỏ ứng dụng vào stub không cần sửa code. Không cần webhook có chữ ký: `UNIHUB_PAYMENT_STRIPE_WEBHOOK_SECRET` để trống trên cluster, nên mọi webhook bị từ chối — ghi rõ ở §5. (ii) Chỉ có seeder Student (`DevStudentSeedRunner`, `@Profile("dev")`, là bean duy nhất có `@Profile` trong `src/main`); tài khoản Organizer/Admin/Check-in Staff không có cách tạo nào. **Mở rộng seeder thành tài khoản có role** (Organizer, Admin, Check-in Staff), vẫn đi qua use case của module User. **Không** dùng script `psql` ghi thẳng vào `users` (vi phạm "module là người ghi duy nhất bảng của mình"). Cluster chạy `SPRING_PROFILES_ACTIVE=dev`: hiện không bật thêm bean nào khác, và `src/main/resources` (gồm `logback-spring.xml`) không có `springProfile`/`on-profile` nào (đã kiểm 2026-11-05; task 5.8 kiểm lại).
  - Đề xuất từng mục: **Mailpit** (SMTP host là cấu hình bắt buộc; image ghim như compose); **MinIO** với `UNIHUB_OBJECT_STORAGE_CREATE_BUCKET=true` thay cho Job tạo bucket; **stub Stripe và stub AI bằng WireMock** chạy trong namespace (`mocking-guidelines.md` §1: provider bên ngoài vẫn là stub ngay cả trong E2E), mapping commit trong `k8s/`; **legacy sync tắt** (như mặc định); **Postgres replica** dựng bằng `pg_basebackup` như `ReplicaIntegrationTestSupport` (init container trên `postgres:16-alpine`), mỗi image cùng phiên bản với `docker-compose.yml`.
  - **Phải kiểm trước khi chốt:** (i) đường xác nhận thanh toán khi gateway là stub: charge trả về đồng bộ, hay cần webhook có chữ ký (stub không ký được bằng secret thật — có thể dùng một `UNIHUB_PAYMENT_STRIPE_WEBHOOK_SECRET` chỉ dành cho cluster và một script ký); (ii) tài khoản cho E2E: Student từ seeder dev (`UNIHUB_DEV_SEED_STUDENT_PASSWORD`), còn Organizer/Admin lấy từ đâu (`user.md` §10).
  - Các stub là client trong cluster, nên cùng chịu quy tắc của P4 (Q15).
  - **Đề xuất: như trên.** → `deployment-and-cicd.md` §2, §6; `mocking-guidelines.md` §1
- [ ] `[→ Mục 5]` **Q7 — ConfigMap/Secret theo tên biến thật.**
  - **✅ Chốt 2026-11-05 (D87) — owner giao Claude Code + advisor chốt thay, owner xác nhận sau:** như đề xuất; cộng: `overlays/dev` có 0 replica nhưng datasource replica vẫn cần URL, nên `unihub.datasource.replica.*` trỏ vào `postgres-master` (giống compose, nơi Master kiêm Replica); `overlays/loadtest` trỏ vào `postgres-replica`. Cấu hình datasource: `spring.datasource.*` + `unihub.datasource.replica.*` (`DataSourceRoutingConfig`, `ReplicaDataSourceProperties`). Không thêm profile `kubernetes`.
  - Đề xuất: ConfigMap và `secret.example.yaml` lập **từ `env.example` + `application.yml`**, không từ blueprint (C46). Secret tạo bằng một script commit trong repo (`k8s/scripts/create-secrets.sh`) đọc giá trị từ môi trường của người chạy; khóa vé dùng `apps/backend/scripts/generate-ticket-key.sh`; không file nào có giá trị thật được commit (Gitleaks). Không thêm Spring profile `kubernetes` trừ khi có cấu hình thật sự chỉ dành cho cluster — kiểm `SPRING_PROFILES_ACTIVE` trong blueprint có tác dụng gì không.
  - Một test tĩnh: mọi biến bắt buộc lúc khởi động (Redis, SMTP host, khóa vé, JWT) có mặt trong ConfigMap hoặc `secret.example.yaml`.
  - **Đề xuất: như trên.** → `deployment-and-cicd.md` §2

### 0.2 INV-15 — migration (M-58)

- [ ] `[→ M-58]` **Q8 — Hình thức bằng chứng của INV-15.** `test-strategy.md` §2 ghi INV-15 cần Testcontainers = **Y**, nên một lần diễn tập rollout bằng tay **không đủ**. Có vấn đề "con gà quả trứng": chưa có image N-1 nào được publish.
  - **✅ Chốt 2026-11-05 (D88) — owner giao Claude Code + advisor chốt thay, owner xác nhận sau:** (a).
  - (a) **IT per-push `MigrationCompatibilityIT`**: Postgres Testcontainers migrate tới HEAD; khởi động **jar của commit N-1** (commit cha của commit thêm migration mới nhất — hiện là commit thêm `V8`) trên schema đó, cùng Redis/RabbitMQ của test; chạy một tập đọc/ghi qua HTTP (đăng nhập, catalog, đăng ký free, đọc registration). Lấy jar N-1: build từ `git worktree` trong CI cho tới khi M-66 có image, sau đó kéo image `<sha N-1>` từ Docker Hub. **Cộng** một test tĩnh "expand/contract": migration mới không được `DROP`/`RENAME` cột/bảng, đổi kiểu, hay `SET NOT NULL` không có default, trừ khi có chú thích giải trình và đã qua một bản phát hành. **Cộng** diễn tập rollout trên cluster (8.4) làm nửa triển khai.
  - (b) Chỉ test tĩnh + diễn tập rollout: cần owner **miễn** yêu cầu Testcontainers của INV-15 — là nới một bất biến, không đề xuất.
  - Chi phí: build thêm một jar (khoảng 1–2 phút) trên job `integration-test` đã 16–17 phút.
  - **Đề xuất: (a).** → `test-strategy.md` §2 (ghi cách chứng minh), `integration-testing.md`, `database-schema.md` §10 (quy tắc expand/contract), `test-matrix.md` M-58

### 0.3 INV-16 — tự phục hồi (M-59)

- [ ] `[→ M-59]` **Q9 — Kill-pod: cách làm, giới hạn lỗi, tắt máy êm.** `resilience-testing.md` §2.8 còn ba TODO (C54).
  - **✅ Chốt 2026-11-05 (D89) — owner giao Claude Code + advisor chốt thay, owner xác nhận sau:** như đề xuất.
  - Đề xuất: (i) **thêm tắt máy êm**: `server.shutdown: graceful`, `spring.lifecycle.timeout-per-shutdown-phase` (vd. 20 s), `preStop` `sleep 5` để Endpoint kịp rút pod trước khi Tomcat dừng, `terminationGracePeriodSeconds` 30 — lúc đó giới hạn của 7a là **0 request 5xx** (chỉ cho phép lỗi kết nối của request đang bay, có đếm); (ii) `failureThreshold` ghi rõ trong manifest (3), không dựa vào mặc định; (iii) 7b "chạy nhưng hỏng": đóng băng tiến trình từ node (`minikube ssh` → `crictl`/`docker pause` container), vì `SIGSTOP` lên PID 1 từ trong container bị kernel bỏ qua; không thêm công tắc test vào code ứng dụng.
  - Bằng chứng: script commit trong repo (`k8s/scripts/kill-pod.sh`), traffic thăm dò qua gateway bằng K6 có sẵn ở `tests/load/k6/` hoặc vòng `curl`, kết quả ghi vào `docs/testing/reports/phase-7-cluster-checks.md`. INV-16 có Testcontainers = N, nên lần chạy có ghi lại là bằng chứng hợp lệ (người chạy: Q22).
  - Đóng luôn `phase-6.md` §5 dòng 2 (crash thấy được như một lần restart): xem Q19.
  - **Đề xuất: như trên.** → `resilience-testing.md` §2.8, `deployment-and-cicd.md` §2 (probe, preStop)

### 0.4 INV-17 — rate limiting (M-61, M-62, M-68)

- [ ] `[→ M-61, M-62]` **Q10 — Thiết kế rate limiter.** Thông số đã có ở `distributed-coordination.md` §3 (5 / 10 s theo user cho đăng ký, 100 / 60 s theo IP, `RRateLimiter`, fail-open khi Redis chết).
  - **✅ Chốt 2026-11-05 (D90) — owner giao Claude Code + advisor chốt thay, owner xác nhận sau:** như đề xuất, với phạm vi theo **(iv), không phải (iii) của bản nháp**: `requirement.md` §2.5 đòi giới hạn "a given **user/device**", nên (iii) (request có token không chịu budget chung nào ngoài đăng ký) bỏ ngỏ một user đã đăng nhập bắn vào mọi endpoint khác. (iv): **budget chung 100 / 60 s theo danh tính** — key `userId` khi token hợp lệ, key IP khi chưa xác thực (gồm login); **budget đăng ký 5 / 10 s theo user** cho `POST /api/v1/registrations`; **webhook thanh toán được miễn** mọi limiter (đã có chữ ký và gateway thử lại từ ít IP). Sinh viên sau NAT của trường và các máy staff sau một NAT không chia chung budget vì đều có token. Filter budget chung đứng **sau** filter xác thực bearer (để biết `userId`) và trước phân quyền. Thêm một key `unihub:ratelimit:global-user:{userId}` vào `distributed-coordination.md` §3, §5. **Token sai hay giả không được thoát limiter:** request mang `Authorization: Bearer` không hợp lệ bị filter xác thực trả `401` trước khi tới limiter, nên **authentication entry point trừ budget IP** cho mọi request kết thúc ở trạng thái chưa xác thực (gồm token sai); quá budget thì trả `429` thay cho `401`. **`429` thành câu trả lời có thể của mọi operation không được miễn**, trong khi contract hiện chỉ khai báo nó trên vài operation và `contract-diff` chỉ so cái đã khai báo: sửa `packages/api-contract` và annotation springdoc của **mọi** controller trong cùng commit, webhook là operation duy nhất không có `429`. Đã kiểm: Web poll số ghế mỗi 5 s và báo cáo mỗi 10 s (12 và 6 lần/phút mỗi tab), dưới xa 100 / 60 s của một user.
  - Đề xuất: hai filter trong `shared.infrastructure` (web): **per-IP** (phạm vi theo câu hỏi bên dưới), đứng trước Spring Security (để chặn cả nhồi mật khẩu vào login); **per-user** chỉ cho `POST /api/v1/registrations`, đứng sau xác thực. `trySetRate(OVERALL, rate, interval, keepAlive = 2 × interval)` để key tự hết hạn (không thì key theo IP tăng mãi). `429` là problem+json có `correlationId` và `Retry-After` (giây, làm tròn lên). **Fail-open có giới hạn thời gian và có đếm**: lời gọi Redis có timeout ngắn (Redis bị `pause` không được làm treo request), mỗi lần bỏ qua tăng `unihub_rate_limit_bypassed_total{reason}` (đăng ký sẵn ở 0, như D68) và có alert Warning — như mọi đường fail-open khác của dự án.
  - **Câu hỏi phạm vi cho owner:** `distributed-coordination.md` §3 mô tả limiter per-IP là để chặn lạm dụng **chưa xác thực**. Áp lên mọi `/api/**` sẽ chặn cả: webhook thanh toán có chữ ký (gateway thử lại từ ít IP; trong cluster là một pod stub), `POST /api/v1/checkin/sync` từ nhiều máy staff sau một NAT của hội trường, sinh viên đã đăng nhập sau NAT của trường. (i) Per-IP chỉ cho request **chưa xác thực** (gồm login), request có token chỉ chịu per-user; (ii) per-IP cho mọi request; (iii) như (i) và loại webhook khỏi mọi limiter. **Đề xuất (bản nháp): (iii)** — đã thay bằng (iv) ở dòng chốt.
  - **Công tắc cấu hình** `unihub.rate-limit.enabled`: bật mặc định và trên cluster; **tắt** trong bộ `integrationTest` chung (mọi IT chạy từ một IP `127.0.0.1` sẽ chạm 100 / 60 s), chỉ bật trong IT của rate limiter. Ghi chú cho Phase 8: K6 bắn từ một IP sẽ bị chặn — quyết định ở Phase 8, không ở đây.
  - **Đề xuất: như trên.** → `distributed-coordination.md` §3, §4, §5; `observability.md` §4, §6; `backend-coding-standards.md`; `packages/api-contract` (kiểm `429` đã khai báo trên đúng các operation)
- [ ] `[→ M-62]` **Q11 — Lấy IP client sau gateway (TODO của S-16).** Header `X-Forwarded-For` do client gửi không được làm reset ngân sách.
  - **✅ Chốt 2026-11-05 (D91) — owner giao Claude Code + advisor chốt thay, owner xác nhận sau:** như đề xuất. Với Q10 (iv), smoke và Playwright đều có token nên không chia budget theo IP của máy host; chỉ request chưa xác thực (trang công khai, login) đi chung một IP qua `minikube tunnel`.
  - Đề xuất: chỉ tin **một** proxy (gateway): với Istio gateway, `meshConfig.defaultConfig.gatewayTopology.numTrustedProxies: 1`; phía ứng dụng `server.forward-headers-strategy: native` với `RemoteIpValve` chỉ tin dải pod CIDR của cluster. Địa chỉ dùng là địa chỉ **gateway nối thêm** (phải nhất), không bao giờ là giá trị trái nhất do client viết. Test: S-16 (a) xoay `X-Forwarded-For` không reset ngân sách.
  - Hệ quả (sau Q10 (iv)): trên Minikube mọi request từ máy host đi ra cùng một IP (qua `minikube tunnel`), nhưng chỉ request **chưa xác thực** dùng key IP; smoke và Playwright đã đăng nhập dùng key `userId`.
  - **Đề xuất: như trên.** → `security-testing.md` §2.2 (S-16), `distributed-coordination.md` §3
- [ ] `[→ M-61, M-68]` **Q12 — Test hai instance.** Đề xuất: dùng lại `MultiInstanceHarness` (không viết base class mới): S-13 (6 lần đăng ký của một user xen kẽ hai instance → đúng 5 được nhận, lần 6 `429`), S-14 (101 request một IP → lần 101 `429`), Redis `pause` → request vẫn qua (fail-open) trong giới hạn thời gian và bộ đếm tăng; **M-68**: token cấp ở instance A dùng được ở B (không có trạng thái phiên theo instance), đọc/ghi qua cả hai. Đo thời gian thêm vào `integration-test`. → `security-testing.md` §2.2 (bỏ TODO), `integration-testing.md` §2, `test-matrix.md` M-61, M-68
  - **✅ Chốt 2026-11-05 (D92) — owner giao Claude Code + advisor chốt thay, owner xác nhận sau:** như đề xuất.

### 0.5 Mesh, mTLS STRICT (P2–P4, INV-20, M-63)

- [ ] `[→ P2]` **Q13 — Đường Ingress → `backend-api`.** Theo Q3: nếu (b) Istio gateway, P2 đạt do thiết kế, bằng chứng là S-19 + kiểm `connection_security_policy="mutual_tls"` trên hop gateway → pod. Nếu (a) ingress-nginx: inject namespace `ingress-nginx` (ưu tiên) hoặc một ngoại lệ hẹp ghi rõ là ngoại lệ. → `deployment-and-cicd.md` ADR Mesh (a)
  - **✅ Chốt 2026-11-05 (D93) — owner giao Claude Code + advisor chốt thay, owner xác nhận sau:** theo Q3 (b): P2 đạt do thiết kế, bằng chứng là hop gateway → pod `mutual_tls` + S-19.
- [ ] `[→ P3]` **Q14 — Job Flyway thoát sidecar.** Đề xuất: sidecar gốc của Kubernetes (Istio đặt `istio-proxy` thành init container `restartPolicy: Always`, tự dừng khi container chính kết thúc) — kiểm trên đúng phiên bản Q1 và bật nếu cần. Bằng chứng: Job đạt `Complete` khi namespace được inject, `make migrate` không hết thời gian. **Cùng quyết định này cũng chi phối replica Postgres** (5.3): init container `pg_basebackup` cần mạng, và dưới injection nó chỉ chạy được nếu `istio-proxy` đã chạy trước nó — tức là cần sidecar gốc. Không có sidecar gốc thì M-60 hỏng ngay khi bật injection, trước cả STRICT. Không có phương án dự phòng `quitquitquit` (blueprint đã loại); nếu sidecar gốc không chạy được, **dừng lại và đưa owner**. → `deployment-and-cicd.md` ADR Mesh (b), §4
  - **✅ Chốt 2026-11-05 (D94) — owner giao Claude Code + advisor chốt thay, owner xác nhận sau:** sidecar gốc, cho cả Job Flyway **và** init container `pg_basebackup` của replica. Điều kiện dừng như đã ghi.
- [ ] `[→ P4]` **Q15 — Stack observability và các stub trong mesh.**
  - **✅ Chốt 2026-11-05 (D95) — owner giao Claude Code + advisor chốt thay, owner xác nhận sau:** (a), namespace `unihub-observability` có inject. **Điều kiện dừng:** nếu Prometheus không scrape được bằng chứng chỉ của sidecar trên Istio 1.31, đưa owner — không tự thêm `portLevelMtls` ngoại lệ.
  - (a) **Inject** Prometheus, Grafana, Tempo; Prometheus scrape qua mTLS theo cách Istio hướng dẫn (sidecar ghi chứng chỉ ra volume dùng chung, `OUTPUT_CERTS`, Prometheus dùng chứng chỉ đó cho job scrape pod), không bắt Prometheus đi qua outbound của sidecar. WireMock, Mailpit, MinIO nằm trong namespace `unihub` nên tự được inject.
  - (b) `PeerAuthentication` `portLevelMtls: PERMISSIVE` trên 8081 và 15692: một ngoại lệ hẹp, làm yếu "no exempted hop".
  - Đặt stack observability ở namespace `unihub` hay namespace riêng có inject: đề xuất **namespace riêng `unihub-observability` có `istio-injection: enabled`**.
  - **Đề xuất: (a).** → `deployment-and-cicd.md` ADR Mesh (c), §2; `observability.md` §8
- [ ] `[→ M-63]` **Q16 — Bằng chứng INV-20 (S-19) và thứ tự chuyển STRICT.**
  - **✅ Chốt 2026-11-05 (D96) — owner giao Claude Code + advisor chốt thay, owner xác nhận sau:** như đề xuất.
  - Đề xuất: script `k8s/scripts/check-mtls.sh`: từ một pod ở namespace **không** inject (`unihub-probe`), mở kết nối plaintext tới mọi cổng của `unihub` (8080, 8081, 5432, 6379, 5672, 15692, 9000, cổng SMTP của Mailpit, WireMock) → **bị từ chối**; từ một pod có sidecar → thành công. Cộng metric `istio_requests_total{connection_security_policy="mutual_tls"}` cho mọi cặp nguồn–đích.
  - **Thứ tự:** P2, P3, P4 đạt → đổi `mode: STRICT` → **chạy lại toàn bộ bằng chứng trên cluster dưới STRICT** (smoke, Playwright, kill-pod, M-64/M-65, P1). Mọi bằng chứng cluster ghi vào `phase-7.md` phải là của cấu hình cuối cùng (STRICT); lần chạy dưới PERMISSIVE chỉ là luyện tập.
  - **Đề xuất: như trên.** → `security-testing.md` §2.2 (S-19), `test-plan.md` §1

### 0.6 TLS, Ingress, correlation

- [ ] `[→ M-67]` **Q17 — Chứng chỉ TLS cho `unihub.local` và bằng chứng M-67.**
  - **✅ Chốt 2026-11-05 (D97) — owner giao Claude Code + advisor chốt thay, owner xác nhận sau:** (a).
  - (a) Script `openssl` (commit trong repo) tạo một CA cục bộ và chứng chỉ cho `unihub.local`; Secret tạo tay (`kubectl create secret tls`), không commit khóa. Playwright tin CA đó (không dùng `ignoreHTTPSErrors`). HTTP cổng 80 chuyển hướng sang HTTPS; chỉ TLS ≥ 1.2.
  - (b) `mkcert`: tiện hơn, thêm một công cụ.
  - (c) cert-manager: thêm một thành phần cluster, không tài liệu nào gọi tên.
  - M-67 chứng minh: request HTTP thường không tới được API (chuyển hướng hoặc bị từ chối); bắt tay TLS thành công với CA cục bộ; smoke và Playwright chạy qua `https://`.
  - **Đề xuất: (a).** → `deployment-and-cicd.md` §2, `test-matrix.md` M-67
- [ ] `[→ phase-6.md §5 dòng 3]` **Q18 — Gắn correlation ID ở Ingress** (`requirement.md` §3.1, C42 của Phase 6).
  - **✅ Chốt 2026-11-05 (D98) — owner giao Claude Code + advisor chốt thay, owner xác nhận sau:** (a).
  - (a) Gateway (Envoy) bắt đầu trace W3C và gửi `traceparent` vào; ứng dụng tiếp nối trace (D65 giữ nguyên: correlation ID = trace ID, giờ là trace ID **do gateway sinh**). Tỉ lệ lấy mẫu của gateway đặt 10% (Telemetry API) để `ParentBased` của ứng dụng đi theo đúng quyết định đó; span của gateway gửi về Tempo.
  - (b) Giữ ứng dụng tự sinh, ghi thành sai lệch có tên so với SRS.
  - Bằng chứng: trên cluster, `X-Correlation-Id` của response bằng trace ID có span của gateway trong Tempo.
  - **Đề xuất: (a)** nếu Q3 = (b). → `observability.md` §1, §8

### 0.7 Observability trong cluster (P1)

- [ ] `[→ P1, M-65]` **Q19 — Manifest stack, P1 "có hiệu lực", và các rule còn mở từ Phase 6.**
  - **✅ Chốt 2026-11-05 (D99) — owner giao Claude Code + advisor chốt thay, owner xác nhận sau:** như đề xuất (gồm giới hạn của `unihub-app-restarted`); **không cấu hình contact point** — vẫn là quyết định của owner, ghi ở §5.
  - Manifest: `k8s/observability/` (Deployment/Service của Prometheus, Grafana, Tempo; scrape bằng `kubernetes_sd_configs` theo annotation pod; `configMapGenerator` đọc `../../observability/`), đúng gợi ý C43 của Phase 6. Grafana: mật khẩu admin từ Secret tạo tay.
  - **P1 có hiệu lực** nghĩa là: trên cluster đã deploy, rule `unihub-dependency-down` được nạp và đánh giá, và khi Redis (rồi RabbitMQ) bị scale về 0, rule chuyển `Firing` trong Grafana và về `Normal` sau khi khôi phục (đi chung với M-65).
  - Rule còn mở (`phase-6.md` §5 dòng 7): đề xuất **thêm** (i) `unihub-app-target-down` (`up{job="unihub-backend"} == 0` hoặc vắng mặt trong 1 phút, Critical) và (ii) `unihub-app-restarted` (`changes(process_start_time_seconds[10m]) > 0`, Warning) — đóng dòng 2 và 7b. **Giới hạn:** rule này chỉ thấy container **khởi động lại tại chỗ** (Liveness giết, JVM crash — đúng điều dòng 2 hỏi); một pod bị **xóa** quay lại với tên và IP mới, tức là một chuỗi mới dưới `kubernetes_sd`, nên `changes()` không thấy gì — lúc đó chỉ có số target giảm trong chốc lát. Ghi giới hạn này, không giả vờ rule bắt được cả hai; (iii) sửa công thức replica lag thêm điều kiện "LSN đã nhận = LSN đã replay thì lag = 0" (dòng 7c), vì `overlays/loadtest` giờ có replica thật.
  - **Contact point** vẫn là quyết định của owner. Đề xuất: không cấu hình (alert hiện trong UI Grafana là đủ cho P1 theo chữ của `test-plan.md`), trừ khi owner chọn một kênh.
  - **Đề xuất: như trên.** → `observability.md` §4, §6, §8; `deployment-and-cicd.md` §2; `test-plan.md` P1

### 0.8 E2E, smoke, ai chạy bằng chứng cluster

- [ ] `[→ M-69, M-70]` **Q20 — Playwright.**
  - **✅ Chốt 2026-11-05 (D100) — owner giao Claude Code + advisor chốt thay, owner xác nhận sau:** như đề xuất.
  - Đề xuất: thư mục **`tests/e2e/`** ở gốc repo (cạnh `tests/load/`), `package.json` riêng, Playwright ghim phiên bản ≥ 2 tuần tuổi; ba engine Chromium, Firefox, WebKit (lý do chọn Playwright ở `e2e-testing.md` §2 là WebKit); chạy tay sau `make deploy-*`, không có job CI (`e2e-testing.md` §3). Kịch bản: **M-69** — khám phá catalog → đăng ký workshop miễn phí → xem vé QR; đăng ký workshop trả phí → checkout bằng token test (stub Stripe) → `CONFIRMED` → vé QR; **M-70** — Organizer tạo, publish, sửa, đổi lịch một workshop; Admin/Organizer đọc trang báo cáo `/admin/workshops/:id/report`. Báo cáo HTML không commit; tóm tắt (số test, engine, thời gian, commit, overlay) ghi vào `phase-7-cluster-checks.md`.
  - Lưu ý: tài khoản (Q6 ii). Với Q10 (iv), mỗi user có budget riêng (gồm 5 / 10 s cho đăng ký), và trạng thái "đã đăng ký" của một user va nhau giữa các engine: **mỗi engine/worker dùng một tài khoản riêng** (seeder tạo N Student).
  - **Đề xuất: như trên.** → `e2e-testing.md` §1–§3 (bỏ câu "no code exists yet"), `test-matrix.md` M-69, M-70
- [ ] `[→ M-60, M-64, M-65]` **Q21 — Nội dung smoke test.**
  - **✅ Chốt 2026-11-05 (D101) — owner giao Claude Code + advisor chốt thay, owner xác nhận sau:** như đề xuất.
  - Đề xuất: `k8s/scripts/smoke.sh` (commit, chạy lại được): Job Flyway `Complete`; mọi pod Ready; `/actuator/health/readiness` và nhóm `dependencies` `UP` (qua `port-forward` cổng 8081); đăng nhập, catalog, đăng ký free qua `https://unihub.local`; email xác nhận có trong Mailpit (outbox → RabbitMQ → consumer chạy trong cluster); target Prometheus `UP`; trace của request có trong Tempo. **M-60**: `pg_stat_replication.state = 'streaming'` và độ trễ nhỏ; xóa pod `postgres-master-0` → quay lại đúng tên và PVC, dữ liệu còn. **M-64**: stub Stripe bị tắt/lỗi → breaker `OPEN`, mọi pod API vẫn Ready, catalog vẫn phục vụ qua gateway. **M-65**: Redis rồi RabbitMQ scale về 0 → pod vẫn Ready, `dependencies` `DEGRADED`, đăng ký trả `503` đúng như INV-08, P1 `Firing`.
  - **Đề xuất: như trên.** → `resilience-testing.md` §2.2 (1b), §2.3 (2b), `test-matrix.md` M-60, M-64, M-65
- [ ] `[→ mọi dòng cluster]` **Q22 — Ai chạy và ai chấp nhận bằng chứng trên cluster.** Phase 6 để M-57 ở `Drafted` vì một lần chạy của Claude Code không được owner nói rõ là chấp nhận. Phase 7 có nhiều bằng chứng tay hơn hẳn (smoke, Playwright, kill-pod, S-15, S-16, S-19, M-60, M-64, M-65, P1, diễn tập rollout).
  - **✅ Chốt 2026-11-05 (D102) — owner giao Claude Code + advisor chốt thay, owner xác nhận sau:** (b) **về thủ tục**: Claude Code chạy và ghi lại. Việc **chấp nhận** bằng chứng không ai chốt thay được: owner nói rõ chấp nhận hay không từng nhóm khi ký `phase-7.md`; dòng matrix chỉ `Passing` sau câu đó.
  - (a) Owner tự chạy từng script.
  - (b) Claude Code chạy và ghi lại (lệnh, output, ảnh chụp khi cần) vào `phase-7-cluster-checks.md`; ở sign-off owner **chấp nhận từng lần chạy bằng một câu rõ ràng** (một dòng điều kiện B có ô "chấp nhận / không chấp nhận" cho từng nhóm); dòng matrix chỉ `Passing` khi được chấp nhận.
  - (c) Như (b), cộng owner tự chạy lại một lần đại diện (smoke + Playwright) trên máy mình.
  - **Đề xuất: (b)**, chốt ngay bây giờ để Phase 7 không ký với một nửa dòng `Drafted`. → `phase-template.md` (không sửa; ghi vào `phase-7.md`), `test-matrix.md` §4.4

### 0.9 CI stage 5 (M-66)

- [ ] `[→ M-66]` **Q23 — Job `build-and-push`.**
  - **✅ Chốt 2026-11-05 (D103) — owner giao Claude Code + advisor chốt thay, owner xác nhận sau:** như đề xuất (chờ secret của Q4).
  - Đề xuất: `needs: [contract-diff, secret-scan-gitleaks, web]` (thêm `web` vì image Web được build — Q5), chỉ trên `push` vào `main`; build hai image `<namespace>/unihub-backend` và `<namespace>/unihub-web`, tag `<git-sha>` + `latest`, multi-arch (Q4), cache buildx `type=gha`. **Cộng** upload `build/reports/tests` của `integration-test` với `if: always()` (`phase-6.md` §5 dòng 10; giúp điều tra M-38). Không thêm công cụ quét image mới (Trivy …) — ngoài scope, ghi vào §5 nếu owner muốn.
  - Bằng chứng M-66: run CI xanh có job `build-and-push`, digest image trên Docker Hub ứng với SHA đó, và cluster kéo đúng digest ấy.
  - **Đề xuất: như trên.** → `deployment-and-cicd.md` ADR CI Job Structuring, §5, §7

### 0.10 Nêu ra với owner, không làm lặng lẽ

- [ ] `[— không dòng]` **Q24 — Các mục mở từ Phase 6 mà Phase 7 không tự xử lý.** M-57 `Drafted` (chờ owner chấp nhận lần chạy 6.5 — hoặc chạy lại trên cluster ở Mục 6, nếu owner muốn dùng lần đó làm bằng chứng); M-38 `Flaky — investigate` (upload báo cáo test ở Q23 là việc duy nhất Phase 7 làm cho nó); sáu dương tính giả OWASP và CVE `httpclient5`/`httpcore5`, CVE-2025-7962; cổng 8081 đụng Expo Metro (trên cluster không đụng; giữ nguyên trừ khi owner đổi). **Đề xuất:** Phase 7 chỉ đụng tới những mục đã nêu ở Q19 và Q23; phần còn lại giữ ở §5 với nhãn "owner". → `phase-7.md` §5
  - **✅ Chốt 2026-11-05 (D104) — owner giao Claude Code + advisor chốt thay, owner xác nhận sau:** như đề xuất.

---

## 0A. Decisions (ADR) — D81–D104 (Q1–Q24) — ✅ chốt 2026-11-05 (owner xác nhận sau)

Owner giao Claude Code thảo luận với advisor để chốt thay (2026-11-05: "Bạn hãy thảo luận với advisor để chốt các vấn đề trên cho tôi"). Đó là lời giao chốt, **không** phải xác nhận từng dòng: trạng thái các dòng D là "owner xác nhận sau" (cùng cách ghi với 0A của Phase 6). Bốn chỗ đổi so với bản nháp: **Q1 → Istio 1.31.x** (1.29 đã hết hỗ trợ theo trang chính thức), **Q10 → phạm vi (iv)** (theo chữ "user/device" của SRS §2.5), **Q3 (b) là điều kiện có tên ở sign-off**, **Q22: thủ tục chốt, việc chấp nhận bằng chứng là của owner**. Hai dòng phụ thuộc việc owner phải làm (Q2 bộ nhớ Docker, Q4 secret Docker Hub). Ô ở Mục 0 chưa tick: chờ tài liệu sở hữu được sửa.

| D | Q | Quyết định (đề xuất) | AC | Test / bằng chứng |
|---|---|---|---|---|
| D81 | Q1 | Minikube K8s 1.35.1 + Istio 1.31.x (trang chính thức: 1.29 hết hỗ trợ 2026-10-15); dừng nếu precheck lỗi | AC81.1 `istioctl x precheck` sạch. AC81.2 Sidecar gốc khả dụng | Ghi phiên bản thật vào `phase-7-cluster-checks.md` |
| D82 | Q2 | Docker ~12 GB (owner nâng), minikube 10g/6 CPU; giảm số của `loadtest`, ghi sai lệch; dừng nếu vẫn OOM | AC82.1 `overlays/loadtest` + Istio + observability đều Ready, không pod nào `OOMKilled` trong lần chạy bằng chứng | Báo cáo cluster (tài nguyên đo được) |
| D83 | Q3 | Istio ingress gateway (`Gateway`/`VirtualService`) thay ingress-nginx — điều kiện có tên ở sign-off | AC83.1 Mọi traffic ngoài vào qua gateway. AC83.2 Không có `PeerAuthentication` ngoại lệ cho hop gateway → pod | S-19, `smoke.sh` |
| D84 | Q4 | Multi-arch, builder native, kéo theo SHA; owner tạo secret Docker Hub | AC84.1 `docker manifest inspect` có `amd64` và `arm64`. AC84.2 Pod chạy đúng digest của run CI | Run CI, `kubectl get pod -o jsonpath` |
| D85 | Q5 | Image Web nginx cùng origin, CSP bằng header | AC85.1 Response của `/` có header `Content-Security-Policy` với `frame-ancestors`. AC85.2 Không lỗi CSP trong console khi chạy Playwright | 9.4, Playwright |
| D86 | Q6 | Mailpit, MinIO, WireMock Stripe (confirm + retrieve, không webhook)/AI, replica `pg_basebackup`; seeder mở rộng cho Organizer/Admin/Check-in Staff | AC86.1 Smoke thấy email trong Mailpit. AC86.2 Replica `streaming`. AC86.3 Đăng nhập được bằng tài khoản Organizer và Admin do seeder tạo | `smoke.sh`, M-60 |
| D87 | Q7 | ConfigMap/Secret theo tên biến thật; script tạo Secret; replica URL của `dev` trỏ Master | AC87.1 Test tĩnh: mọi biến bắt buộc có mặt. AC87.2 Gitleaks xanh | Test tĩnh, Gitleaks |
| D88 | Q8 | `MigrationCompatibilityIT` (jar N-1 trên schema HEAD) + lint expand/contract + diễn tập rollout | AC88.1 Jar N-1 phục vụ tập đọc/ghi trên schema HEAD. AC88.2 Lint đỏ với một migration `DROP COLUMN` mẫu (fixture). AC88.3 Pod cũ phục vụ không lỗi khi Job migration mới chạy | `MigrationCompatibilityIT`, `MigrationLintTest`, 8.4 |
| D89 | Q9 | Tắt máy êm + preStop; `failureThreshold` rõ; đóng băng từ node | AC89.1 Xóa một pod khi có traffic → 0 phản hồi 5xx, replica về đủ Ready. AC89.2 Pod đóng băng rời rotation trong ≤ 30 s và được restart | `kill-pod.sh`, báo cáo cluster |
| D90 | Q10 | Budget chung 100/60 s theo danh tính (userId hoặc IP), đăng ký 5/10 s theo user, webhook miễn; keepAlive; fail-open có timeout và có đếm; công tắc cấu hình | AC90.1 Lần thứ 6 / 10 s → `429` + `Retry-After`. AC90.2 Redis `pause` → request qua trong ≤ 2 s và `unihub_rate_limit_bypassed_total` tăng. AC90.3 Hai user khác nhau cùng một IP không chia budget; webhook không bao giờ `429`. AC90.4 Hơn 100 request / 60 s với token sai từ một IP → `429` | `RateLimitIT`, unit test filter |
| D91 | Q11 | Tin đúng một proxy; IP do gateway nối thêm | AC91.1 Xoay `X-Forwarded-For` không reset ngân sách | `RateLimitIT` (giả lập header), S-16 trên cluster |
| D92 | Q12 | Dùng `MultiInstanceHarness` cho S-13, S-14, M-68 | AC92.1 Hai instance cộng lại đúng 5 / 100. AC92.2 Token của A dùng được ở B | `RateLimitMultiInstanceIT`, `StatelessInstancesIT` |
| D93 | Q13 | P2 theo Q3 | AC93.1 Hop gateway → pod là `mutual_tls` | `check-mtls.sh` |
| D94 | Q14 | Sidecar gốc cho Job Flyway và init container của replica | AC94.1 Job `Complete` khi namespace được inject, dưới STRICT | `make migrate` dưới STRICT |
| D95 | Q15 | Inject stack observability (namespace riêng), scrape bằng chứng chỉ của sidecar; dừng nếu không được | AC95.1 Target `UP` dưới STRICT. AC95.2 Không `portLevelMtls` ngoại lệ | Prometheus `/targets`, `check-mtls.sh` |
| D96 | Q16 | S-19 bằng script; mọi bằng chứng cluster chạy lại dưới STRICT | AC96.1 Mọi cổng từ chối plaintext từ namespace không inject. AC96.2 Pod có sidecar kết nối được | `check-mtls.sh` |
| D97 | Q17 | CA cục bộ bằng `openssl`, Secret tạo tay | AC97.1 HTTP không tới API. AC97.2 Playwright chạy qua HTTPS với CA cục bộ | 9.4 |
| D98 | Q18 | Gateway sinh `traceparent`, lấy mẫu 10% | AC98.1 `X-Correlation-Id` = trace ID có span của gateway trong Tempo | Báo cáo cluster |
| D99 | Q19 | `k8s/observability/` từ `observability/`; P1 bắn trên cluster; thêm rule app-down, restart; sửa replica lag | AC99.1 Redis scale 0 → `unihub-dependency-down` `Firing`, rồi `Normal`. AC99.2 Container API bị Liveness giết (kịch bản 7b) → `unihub-app-restarted` `Firing`. AC99.3 `ObservabilityProvisioningIT`, `AuditAlertIT` vẫn xanh với rule mới | Báo cáo cluster, IT per-push |
| D100 | Q20 | Playwright ở `tests/e2e/`, ba engine, chạy tay, một tài khoản mỗi engine/worker | AC100.1 Toàn bộ suite xanh trên một lần deploy mới, dưới STRICT | Báo cáo Playwright (tóm tắt) |
| D101 | Q21 | `smoke.sh` gồm M-60, M-64, M-65 | AC101.1 Mọi bước xanh trên một lần deploy mới | `smoke.sh` |
| D102 | Q22 | Claude Code chạy và ghi lại; **owner** chấp nhận từng nhóm khi ký | AC102.1 `phase-7.md` có điều kiện B cho từng nhóm bằng chứng tay | Sign-off |
| D103 | Q23 | `build-and-push` hai image, multi-arch, `needs: web`; upload báo cáo test | AC103.1 Mười job CI xanh trên cùng một SHA | Run CI |
| D104 | Q24 | Mục mở từ Phase 6 giữ cho owner | AC104.1 Có ở `phase-7.md` §5 | Sign-off |

---

## 0B. Đối soát chéo — mâu thuẫn tìm thấy (2026-11-05)

Mỗi mục sửa ở tài liệu được ghi, trước khi code phụ thuộc vào nó. Đánh số C45 trở đi (C45–C62) để không trùng C1–C44.

- [ ] **C45** *(→ Q4)* — `deployment-and-cicd.md` §1: Dockerfile mẫu `COPY build.gradle settings.gradle` nhưng repo dùng `build.gradle.kts`/`settings.gradle.kts`, và mọi thứ nằm dưới `apps/backend/` trong khi job mẫu build với `context: .`. Sửa đường dẫn, context, và cách tách lớp dependency (`./gradlew dependencies` không tải đủ mọi cấu hình — kiểm).
- [ ] **C46** *(→ Q7)* — ConfigMap mẫu dùng `DB_HOST`, `DB_PORT`, `REDIS_HOST`, `RABBITMQ_HOST`, `S3_ENDPOINT`; Secret dùng `JWT_SIGNING_KEY`, `PAYMENT_GATEWAY_API_KEY`, `DB_USER`. Ứng dụng không đọc tên nào trong số đó: tên thật ở `env.example` (`UNIHUB_REDIS_ADDRESS`, `UNIHUB_SECURITY_JWT_SECRET`, `UNIHUB_OBJECT_STORAGE_*`, …), Secret còn thiếu `UNIHUB_REDIS_PASSWORD`, khóa object storage, `UNIHUB_PAYMENT_STRIPE_WEBHOOK_SECRET`. Datasource: `spring.datasource.*` (Master) + `unihub.datasource.replica.*`; `overlays/dev` (0 replica) trỏ replica vào `postgres-master` (D87).
- [ ] **C47** *(→ Mục 5)* — `configMapGenerator` của migration trỏ `../../src/main/resources/db/migration/` (sai: phải là `../../apps/backend/src/...` tính từ `k8s/base`) và chỉ liệt kê `V1`, `V2`; thực tế có 10 file. Thêm một test tĩnh: danh sách `files:` khớp thư mục migration.
- [ ] **C48** *(→ Q8)* — Job Flyway dùng `flyway/flyway:10-alpine` (tag trôi), plugin Gradle `13.8.0`. Ghim image cùng phiên bản với plugin (kiểm tag có tồn tại).
- [ ] **C49** *(→ Mục 5)* — `Makefile` mẫu gọi binary `kustomize` (không cài); `kubectl kustomize --load-restrictor LoadRestrictionsNone` có sẵn (Kustomize v5.8.1 trong kubectl 1.36).
- [ ] **C50** *(→ Q3)* — `ingress.yaml` mẫu dùng annotation đã bỏ `kubernetes.io/ingress.class` (thay bằng `ingressClassName`), và ingress-nginx đã ngừng bảo trì.
- [ ] **C51** *(→ Q15, Q19)* — Service `rabbitmq` mẫu không có cổng 15692 (Prometheus scrape); image của Redis/RabbitMQ/MinIO/Mailpit trong K8s phải trùng compose (`rabbitmq:3.13.7-management`, …) theo cam kết "same images" của §6.
- [ ] **C52** *(→ Mục 5)* — `distributed-coordination.md` §1 nói Redis chạy bằng `StatefulSet`, `deployment-and-cicd.md` §2 nói `Deployment`. Redis không lưu gì bền vững (persistence tắt): đề xuất `Deployment`, sửa §1.
- [ ] **C53** *(→ Q23)* — `deployment-and-cicd.md` §5 "Branch protection … requires five status checks" mâu thuẫn với quyết định dự án không bật branch protection (`test-matrix.md` §4.4). Sửa đoạn đó thành "không áp dụng, có chủ ý".
- [ ] **C54** *(→ Q9)* — `resilience-testing.md` §2.8 còn ba TODO (giới hạn burst, `failureThreshold`, cơ chế 7b); câu "The manifest sets no `preStop` hook" sẽ sai sau Q9.
- [ ] **C55** *(→ Q11, Q12)* — `security-testing.md` §2.2: TODO S-16 (lấy `ipAddress` sau Ingress) và TODO "extend `IntegrationTestSupport`" (đã có `MultiInstanceHarness`).
- [ ] **C56** *(→ Q1)* — Istio 1.24.x (ADR Mesh, §7, `test-plan.md` P3) đã hết hỗ trợ (2025-06-24) và chỉ hỗ trợ K8s 1.28–1.31; đổi thành 1.31.x (D81).
- [ ] **C57** *(→ Mục 5)* — StatefulSet Postgres mẫu `envFrom: backend-api-secrets` với `DB_USER`/`DB_PASSWORD`, trong khi image `postgres` đọc `POSTGRES_USER`/`POSTGRES_PASSWORD`/`POSTGRES_DB`; tên database `unihub` là "assumption" của §4, compose dùng `POSTGRES_DB`.
- [ ] **C58** *(→ Q5, Q6)* — Topology §2 thiếu Web, Mailpit (SMTP host là bắt buộc lúc khởi động), stub Stripe/AI, stack observability; sơ đồ mermaid §2 cũng thiếu.
- [ ] **C59** *(→ Q2)* — ADR Resource Profile và §3: số của `overlays/loadtest` không vừa máy của owner.
- [ ] **C60** *(→ Q6)* — §2 nói replica "with streaming replication configured against `postgres-master`" nhưng không có cơ chế; dùng `pg_basebackup` như `ReplicaIntegrationTestSupport`.
- [ ] **C61** *(→ Q20)* — `e2e-testing.md` §5 và phần mở đầu còn nói "no frontend or backend code exists yet".
- [ ] **C62** *(→ Q3, Q13)* — `test-plan.md` P2 và `architecture.md` §5.4 viết theo ingress-nginx; nếu Q3 = (b), sửa câu chữ của P2 (P2 đạt do thiết kế) — không xóa P2.

---

## 1. Image: backend, Web (sau Q4, Q5, C45)

- [ ] **1.1** `[→ M-66]` `apps/backend/Dockerfile` (hoặc `Dockerfile` ở gốc, theo C45): builder `eclipse-temurin:21-jdk` với `--platform=$BUILDPLATFORM`, runtime `eclipse-temurin:21-jre`, user không root, `JAVA_TOOL_OPTIONS` như §1, `EXPOSE 8080 8081`; `.dockerignore`. Image ghim theo digest hoặc tag cụ thể.
- [ ] **1.2** `[→ M-66]` Chạy thử image với docker-compose hiện có (Postgres/Redis/RabbitMQ/Mailpit/MinIO): khởi động, `/actuator/health` ở 8081, đăng nhập. Ghi kích thước image.
- [ ] **1.3** `[→ M-69, §4.5]` `apps/web/Dockerfile`: stage Node build (`VITE_PAYMENT_TEST_MODE` qua build arg), stage `nginx-unprivileged` với cấu hình SPA fallback và **CSP bằng header** (cùng nội dung với thẻ `<meta>`, thêm `frame-ancestors 'none'`); test tĩnh hoặc Vitest: header khớp policy của `vite-plugins`.
- [ ] **1.4** `[— không dòng]` Gitleaks, `lint`, `web` vẫn xanh; không secret nào nằm trong image (kiểm `docker history`, không `ARG` mang secret).

## 2. CI stage 5 — M-66 (sau Q23; owner đã tạo secret Docker Hub)

- [ ] **2.1** `[→ M-66]` **Owner:** tạo access token Docker Hub, `gh secret set DOCKERHUB_USERNAME` / `DOCKERHUB_TOKEN`, báo namespace.
- [ ] **2.2** `[→ M-66]` Job `build-and-push` theo Q23 (hai image, multi-arch, cache `gha`, `needs: [contract-diff, secret-scan-gitleaks, web]`, chỉ `push`); upload `build/reports/tests` của `integration-test` với `if: always()`.
- [ ] **2.3** **`[M-66]`** Một run trên `main` có mười job xanh; ghi digest của hai image và thời gian job `build-and-push`.

## 3. Rate limiter — M-61, M-68 (INV-17) (sau Q10–Q12)

- [ ] **3.1** `[→ M-61]` Port + adapter Redisson cho limiter trong `shared` (`backend-coding-standards.md`: port ở `shared.application`, adapter ở `shared.infrastructure.out`), `trySetRate` có keepAlive, timeout ngắn cho lời gọi Redis.
- [ ] **3.2** `[→ M-61]` Hai filter theo Q10 (iv): budget chung theo danh tính (sau xác thực bearer, key `userId` hoặc IP), budget đăng ký theo user cho `POST /api/v1/registrations`, webhook miễn; entry point trừ budget IP cho request chưa xác thực (gồm token sai); `429` khai báo trên mọi operation trừ webhook (contract + annotation, cùng commit); `429` problem+json có `correlationId` và `Retry-After`; lấy IP theo Q11 (`forward-headers-strategy`, proxy tin cậy). Bỏ các comment "documented only" ở ba controller; `contract-diff` xanh.
- [ ] **3.3** `[→ M-61]` Công tắc `unihub.rate-limit.enabled` (tắt trong bộ `integrationTest` chung); bộ đếm `unihub_rate_limit_bypassed_total{reason}` đăng ký sẵn ở 0; rule Warning trong `observability/grafana/provisioning/alerting/unihub-rules.yml`; `ObservabilityProvisioningIT` xanh.
- [ ] **3.4** `[→ M-61 · phase-3.md §5]` Unit test filter + `RateLimitIT` (một instance): biên 5/6 và 100/101 (`test-design-techniques.md` §4), `Retry-After`, xoay `X-Forwarded-For` (S-16 a), login > 100 / 60 s (S-16 b), hai user cùng một IP không chia budget, > 100 request token sai từ một IP → `429`, webhook không bao giờ `429`, Redis `stop` **và** `pause` → fail-open trong ≤ 2 s và đếm (đóng mục hoãn "rate limiter failing open" của `phase-3.md` §5 dòng 3; thêm probe vào `RedisOutageIT` nếu hợp).
- [ ] **3.5** **`[M-61]`** `RateLimitMultiInstanceIT` trên `MultiInstanceHarness`: S-13, S-14.
- [ ] **3.6** **`[M-68]`** `StatelessInstancesIT` trên `MultiInstanceHarness`: token cấp ở A dùng được ở B; tạo ở A, đọc ở B; không có `HttpSession` nào được tạo (kiểm cấu hình `SessionCreationPolicy.STATELESS`).
- [ ] **3.7** `[— không dòng]` Đo thời gian thêm vào `integration-test`; không `@Disabled` test nào để bù.

## 4. Tương thích migration — M-58 (INV-15) (sau Q8, C48)

- [ ] **4.1** `[→ M-58]` Test tĩnh `MigrationLintTest`: migration mới nhất không chứa thao tác phá vỡ khi chưa có chú thích giải trình; fixture một migration `DROP COLUMN` làm test đỏ đúng chỗ.
- [ ] **4.2** `[→ M-58]` Cách lấy jar/image N-1 (git worktree + `bootJar` trong CI, hoặc image `<sha N-1>` sau khi Mục 2 xong); script tìm commit N-1 tự động từ lịch sử của thư mục migration. Job `integration-test` cần `fetch-depth: 0` (hiện checkout nông). Ứng dụng N-1 khởi động với **cấu hình bắt buộc của chính commit đó**, không phải của HEAD.
- [ ] **4.3** **`[M-58]`** `MigrationCompatibilityIT` (per-push): schema HEAD + ứng dụng N-1 (container hoặc tiến trình riêng), tập đọc/ghi qua HTTP; ứng dụng HEAD trên cùng schema chạy cùng tập. Đo thời gian thêm.
- [ ] **4.4** `[→ M-58]` Ghi quy tắc expand/contract vào `database-schema.md` §10 và `backend-coding-standards.md`.

## 5. Manifest Kubernetes, Makefile, deploy `dev` (sau Q1–Q7, C46–C52, C57–C60)

- [ ] **5.1** `[→ mọi dòng cluster]` Script dựng cluster: `minikube start` với phiên bản, bộ nhớ, CPU của Q1/Q2; cài Istio (phiên bản Q1, có gateway theo Q3); nếu Q3 dùng tài nguyên Gateway API thì cài CRD Gateway API ghim phiên bản (Kubernetes không kèm sẵn), nếu không thì dùng `Gateway`/`VirtualService` của Istio; ghi phiên bản thật.
- [ ] **5.2** `[→ M-59 …]` `k8s/namespace.yaml`, `k8s/base/` (backend-api có probe trên cổng `management`, `preStop`, `failureThreshold`, tắt máy êm theo Q9; web; Postgres Master; Redis; RabbitMQ (có 15692); MinIO; Mailpit; WireMock Stripe/AI; `peer-authentication.yaml` **PERMISSIVE**; ConfigMap; `secret.example.yaml` ngoài `resources:`), `overlays/dev`, `overlays/loadtest` (số theo Q2).
- [ ] **5.3** `[→ M-60]` Postgres replica: StatefulSet với init container `pg_basebackup`, `pg_hba` cho replication, Service `postgres-replica`; ứng dụng đọc ở replica (INV-03 trên cluster). `overlays/dev` trỏ `unihub.datasource.replica.*` vào `postgres-master` (Q7).
- [ ] **5.4** `[→ M-58]` `k8s/jobs/flyway-migrate-job.yaml` (image theo C48), `configMapGenerator` đủ 10 file + test tĩnh khớp thư mục (C47).
- [ ] **5.5** `[→ mọi dòng cluster]` `Makefile` ở gốc (`cluster-up`, `secrets`, `pre-migration`, `migrate`, `deploy-dev`, `deploy-loadtest`, `smoke`), dùng `kubectl kustomize`; `k8s/scripts/create-secrets.sh` (Q7).
- [ ] **5.6** `[→ M-67]` CA cục bộ và chứng chỉ `unihub.local` (Q17), Secret TLS tạo tay; gateway route `/api/**` → backend, `/` → web; HTTP → HTTPS; hướng dẫn `/etc/hosts` + `minikube tunnel`.
- [ ] **5.7** **`[M-60]`** `make deploy-dev` rồi `make deploy-loadtest` thành công trên cluster mới (dưới PERMISSIVE trước); replica `streaming` **khi namespace đã được inject** (init container `pg_basebackup` cần sidecar gốc, Q14); xóa `postgres-master-0` → cùng tên, cùng PVC, dữ liệu còn. Ghi vào `phase-7-cluster-checks.md` (bản luyện tập — bản bằng chứng chạy lại ở Mục 10 dưới STRICT).
- [ ] **5.8** `[→ M-69, M-70]` Mở rộng seeder dev thành tài khoản có role (Organizer, Admin, Check-in Staff) qua use case của module User; mật khẩu từ biến môi trường, không mặc định; idempotent; unit test + IT; cập nhật `user.md` §10. Kiểm lại: `DevStudentSeedRunner` vẫn là bean `@Profile("dev")` duy nhất (Q6 ii).

## 6. Observability trong cluster — P1 (sau Q15, Q19)

- [ ] **6.1** `[→ P1, P4]` `k8s/observability/` (namespace có inject theo Q15): Prometheus (scrape theo `kubernetes_sd_configs`, chứng chỉ của sidecar), Grafana (Secret admin), Tempo; `configMapGenerator` đọc `observability/`; `MANAGEMENT_OTLP_TRACING_ENDPOINT` trỏ Tempo.
- [ ] **6.2** `[→ P1]` Rule mới theo Q19: `unihub-app-target-down`, `unihub-app-restarted`, sửa replica lag; cập nhật `observability.md` §4, §6; `ObservabilityProvisioningIT` và `AuditAlertIT` xanh.
- [ ] **6.3** `[→ phase-6.md §5 dòng 3]` Tracing ở gateway theo Q18 (Telemetry API, 10%, gửi Tempo).
- [ ] **6.4** **`[P1]`** Trên cluster: Redis scale 0 → `unihub-dependency-down` `Firing` → khôi phục → `Normal`; lặp với RabbitMQ; ảnh chụp màn hình alert ở trạng thái `Firing` (thiếu ở Phase 6). Sửa dòng P1 của `test-plan.md` §1 khi được chấp nhận.

## 7. Istio, P2–P4, STRICT — M-63 (INV-20) (sau Q13–Q16)

- [ ] **7.1** `[→ M-63]` Kiểm mọi pod của `unihub` và `unihub-observability` có `istio-proxy`; probe kubelet vẫn chạy qua sidecar (viết lại probe mặc định của Istio).
- [ ] **7.2** **`[P2]`** Hop gateway → `backend-api` và → `web` là `mutual_tls` (metric Istio hoặc `istioctl x describe`).
- [ ] **7.3** **`[P3]`** `make migrate` với namespace được inject: Job `Complete`, sidecar dừng theo container chính; init container `pg_basebackup` của replica chạy được dưới injection (Q14).
- [ ] **7.4** **`[P4]`** Target Prometheus `UP` qua mTLS; Grafana đọc Prometheus và Tempo; WireMock/Mailpit/MinIO có sidecar.
- [ ] **7.5** `[→ M-63]` Đổi `base/peer-authentication.yaml` sang `mode: STRICT`; sửa ADR Mesh (Status), `test-plan.md` P2–P4, `security-testing.md` (A05 không còn Partial khi 7.6 xong).
- [ ] **7.6** **`[M-63]`** `check-mtls.sh` (Q16): mọi cổng từ chối plaintext từ namespace không inject; pod có sidecar kết nối được.

## 8. Resilience trên cluster — M-59, M-62, M-64, M-65, diễn tập M-58 (sau Q9, Q21; dưới STRICT)

- [ ] **8.1** **`[M-59]`** Kịch bản 7a (`kill-pod.sh`): xóa một pod API khi có traffic thăm dò qua gateway → 0 phản hồi 5xx (theo Q9), Deployment về đủ replica Ready (pod bị xóa là chuỗi mới, `unihub-app-restarted` không bắn — xem Q19).
- [ ] **8.2** **`[M-59]`** Kịch bản 7b: đóng băng một pod từ node → rời rotation (Readiness) và bị restart (Liveness), traffic tiếp tục trên pod khác; thời gian đo được; `unihub-app-restarted` `Firing` (AC99.2).
- [ ] **8.3** **`[M-62]`** S-15: trên `overlays/loadtest` (≥ 2 pod), 6 lần đăng ký nhanh của một user qua `https://unihub.local` → lần 6 `429`, bất kể pod nào phục vụ; S-16 (a) xoay `X-Forwarded-For` qua gateway.
- [ ] **8.4** **`[M-58]`** Diễn tập rollout: deploy image N-1, chạy Job migration của HEAD trong khi pod N-1 đang phục vụ traffic thăm dò (0 lỗi), rồi rollout HEAD.
- [ ] **8.5** **`[M-64]`** Stub Stripe chết/lỗi → breaker `OPEN`, mọi pod API Ready, catalog và đăng ký free vẫn qua gateway (kịch bản 1b).
- [ ] **8.6** **`[M-65]`** Redis rồi RabbitMQ scale 0 → mọi pod Ready, `dependencies` `DEGRADED`, đăng ký `503` (INV-08), P1 `Firing` (kịch bản 2b; đi chung với 6.4).

## 9. E2E, smoke, HTTPS — M-67, M-69, M-70 (sau Q17, Q20, Q21; dưới STRICT)

- [ ] **9.1** `[→ M-69, M-70]` `tests/e2e/` (Playwright ghim, ba engine, CA cục bộ, tài khoản theo Q6 ii); `smoke.sh` theo Q21.
- [ ] **9.2** **`[M-69]`** Hành trình Student (free và trả phí, tới vé QR) xanh trên ba engine.
- [ ] **9.3** **`[M-70]`** Hành trình Organizer/Admin (tạo, publish, sửa, đổi lịch, đọc báo cáo) xanh trên ba engine.
- [ ] **9.4** **`[M-67, §4.5]`** HTTP không tới API; TLS ≥ 1.2; header CSP (có `frame-ancestors`) trên trang Web; không vi phạm CSP nào trong console khi chạy Playwright (đóng luôn mục "browser CSP check" lặp lại được, nếu owner đồng ý coi Playwright là kiểm đó).

## 10. Lần chạy bằng chứng cuối cùng (một lần deploy mới, STRICT)

- [ ] **10.1** `minikube delete` → dựng lại từ đầu bằng `Makefile` với image của **một** SHA đã xanh CI (digest từ 2.3) → `smoke.sh` → Playwright → 8.1–8.6 → 6.4 → 7.6. Mọi output vào `docs/testing/reports/phase-7-cluster-checks.md` (lệnh, phiên bản, SHA, digest, thời gian, ảnh chụp alert `Firing`).
- [ ] **10.2** Ghi tài nguyên đo được (bộ nhớ từng pod, tổng) và nhận xét cho Phase 8 (Q2).

## 11. CI/CD

- [ ] **11.1** Mười job xanh trên một SHA; thời gian `integration-test` trước/sau (rate limiter, hai instance, `MigrationCompatibilityIT`) ghi lại; **không** `@Disabled`/bỏ test INV nào (`test-plan.md` §2).
- [ ] **11.2** `RateLimitMultiInstanceIT`, `StatelessInstancesIT`, `MigrationCompatibilityIT` ≥ 10 lần liên tiếp xanh ở local.
- [ ] **11.3** `contract-diff`, `web`, `mobile`, Gitleaks xanh; delta OWASP do dependency/image mới ghi vào `phase-7.md` §5.

## 12. Đồng bộ tài liệu & Test Matrix

- [ ] **12.1** Sau mỗi task: dòng tương ứng trong `test-matrix.md` §2.2 (Status, `Class#method` hoặc script thật).
- [ ] **12.2** `test-matrix.md` §4.1, §4.2 (gỡ `⚠` của M-60, M-67, M-68), §4.5 (CSP header), §5; đếm lại từ cột Status.
- [ ] **12.3** `deployment-and-cicd.md` (toàn bộ, theo C45–C60), `architecture.md` §1.2/§5.4 (nếu Q3 = b), `distributed-coordination.md` §1, §3, `observability.md` §4, §6, §8, `resilience-testing.md` §2.8, `security-testing.md` §2.2, `e2e-testing.md`, `test-strategy.md` §2 (INV-20 không còn "pending" khi 7.6 xong), `test-plan.md` §1 (P1–P4).
- [ ] **12.4** "Project Status" trong `CLAUDE.md`.

## 13. Đóng Phase 7 (Exit / Sign-off)

Exit criteria của `test-plan.md` §1 dòng Phase 7, đọc đúng chữ:

- [ ] Toàn bộ pipeline CI (stage 1–5) pass từ đầu đến cuối trên một lần đưa vào `main` (M-66).
- [ ] Smoke test — **gồm toàn bộ suite Playwright** — pass trên một lần deploy Minikube mới (M-69, M-70, Mục 10).
- [ ] Test migration chứng minh pod phiên bản cũ không hỏng với schema sau migration khi đang rollout (INV-15, M-58).
- [ ] Test kill-pod chứng minh tự phục hồi (INV-16, M-59).
- [ ] Test rate limit chứng minh không có đường vòng theo từng instance (INV-17, M-61, M-62).
- [ ] **P1–P4 đạt**, và test mTLS chứng minh không hop plaintext nào được chấp nhận (INV-20, M-63).
- [ ] **M-64 (INV-05) và M-65 (INV-08) `Passing`** — hai dòng mang INV, nên **không được hoãn** sang §5 (lời xác nhận ở §6 nói mọi mục hoãn đều không phải INV).
- [ ] M-60, M-67, M-68 `Passing` hoặc ghi ở §5 với lý do.
- [ ] Coverage ≥ 80% (JaCoCo xanh), gồm filter và adapter của rate limiter.
- [ ] Không `TODO`/`FIXME` trong test code; không `@Disabled` trên test của INV-15/16/17/20.
- [ ] Copy `phase-template.md` → `docs/testing/reports/release-sign-offs/phase-7.md`, điền §2–§5 với evidence thật; có **điều kiện B cho từng nhóm bằng chứng tay** (Q22); điều kiện có tên gồm ít nhất: D81–D104 chốt theo ủy quyền, **Q3 thay ingress-nginx bằng Istio gateway** (sửa `architecture.md`); §5 gồm ít nhất: webhook thanh toán bị từ chối trên cluster (secret để trống), sai lệch tài nguyên so với ADR (Q2) và nhận xét cho Phase 8, image Web build ở chế độ thanh toán test, Check-in Client chưa gọi vào cluster, giới hạn per-IP với traffic từ một máy host, các mục mang từ `phase-6.md` §5 chưa đóng (Q24).
- [ ] Owner ký §6 của `phase-7.md`, và **nói rõ chấp nhận hay không** từng nhóm bằng chứng tay.

> Phase 7 chỉ được coi là hoàn tất khi `phase-7.md` tồn tại, mọi ô được tick với evidence thật, và §6 đã ký — **không phải khi cluster chạy được**.

---

## Ghi chú cho Claude Code

- Khi được yêu cầu "tiếp tục Phase 7", đọc file này trước, tìm mục đầu tiên chưa tick theo thứ tự Mục 0 → 0B → 1 → … → 13, và làm mục đó.
- **Mục 0 chốt 2026-11-05 theo ủy quyền** (D81–D104). Trước Mục 1: sửa tài liệu sở hữu và đóng 0B khi owner đồng ý. Mục 2 chờ secret Docker Hub (Q4); Mục 5 chờ bộ nhớ Docker (Q2). Nếu owner đổi một quyết định: sửa dòng Q/D và tài liệu sở hữu trước, rồi mới code. Tôn trọng các điều kiện dừng ở Q1, Q2, Q14, Q15.
- Mục 1–4 không cần cluster (per-push), làm trước; Mục 5–10 cần Minikube và tài nguyên của Q2.
- Không nới INV-15/16/17/20 để qua một bài test: không limiter trong bộ nhớ của từng pod, không chạy Flyway lúc ứng dụng khởi động, không báo INV-20 `Passing` dưới PERMISSIVE, không thêm `PeerAuthentication` ngoại lệ mà không ghi rõ là ngoại lệ.
- Không commit giá trị secret, khóa TLS, khóa ký vé hay token Docker Hub; mọi Secret tạo tay hoặc bằng script đọc từ môi trường.
- Không tự ý tick một mục — chỉ tick sau khi test thật sự pass (hoặc lần chạy cluster đã được ghi lại) và `test-matrix.md` đã được cập nhật tương ứng.
