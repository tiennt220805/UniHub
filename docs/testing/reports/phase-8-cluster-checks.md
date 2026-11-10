# Phase 8 — Kiểm trên cluster Minikube (task 9.1, 9.2, 9.3)

> **Mục đích.** Các spec trình duyệt của Mục 8 chạy trên backend thật cục bộ (Docker, `vite preview`). Chúng không đi qua Istio, không dưới STRICT và không dùng image của CI. File này ghi lần chạy trên cluster của Phase 7, với image CI của commit chạy.
>
> **Đây là bằng chứng do Claude Code chạy, chưa được owner chấp nhận (D102).** Theo luật của Phase 7, M-70, M-82, M-86 và M-88 **chưa** `Passing` cho tới khi owner nói rõ là chấp nhận lần chạy này. Việc đó thuộc 10.1.

## Ai chạy, khi nào, ở đâu

| | |
|---|---|
| Người chạy | Claude Code, trên máy của Nguyễn Thành Tiến, theo yêu cầu "triển khai tiếp mục 9". Owner đồng ý để Claude Code dừng `bootRun`, `vite preview` và `docker compose stop` (phiên 2026-11-10) để nhường bộ nhớ cho Docker VM (11,67 GiB; Minikube xin 10 GiB) |
| Thời điểm | 2026-11-10, 08:50–09:40 UTC |
| Cluster | profile `unihub` có từ Phase 7, **khởi động lại bằng `make cluster-up`** (không `minikube delete`): Kubernetes v1.35.1, Istio 1.31.3, `PeerAuthentication` **STRICT** ở `unihub` và `unihub-observability` (`kubectl get peerauthentication -A` trước khi chạy). Dữ liệu cũ của Phase 7 còn trong PVC, nên migration `V9` chạy trên dữ liệu có sẵn (Flyway Job `Complete`) |
| Overlay | `dev` (1 pod API, không replica Postgres) |
| Mã | lần chạy 1: `01caf83` (CI run 42782014677, mười job xanh). **Lần chạy cuối: `bfc542d`** (CI run 42787514056, mười job xanh) |
| Image của lần chạy cuối | `thanhtien2208/unihub-backend@sha256:6547dd96…2ed6c`, `thanhtien2208/unihub-web@sha256:5e90468d…3480` (đọc từ `imageID` của pod; tag là SHA đầy đủ) |

## 9.1 — CI và thời gian job

Mười job `success` trên `01caf83` (42782014677), `e6a2eec` (42783517762) và `bfc542d` (42787514056).

| | `web` | `integration-test` |
|---|---|---|
| Phase 7 (ba run xanh `f648fd0`, `308e3de`, `39198c4`) | 64 s | 19m15s (trung bình 15 run Phase 7: 19m16s) |
| Phase 8, tám run trước Mục 9 (`7f67ba9` … `98e351b`) | 73 s | 20m01s |
| Phase 8, cộng ba run Mục 9 (11 run) | **82 s** | **20m01s** |

`integration-test` không đổi so với các run Phase 8 trước (cộng khoảng 45 s so với Phase 7). Job `web` dài hơn khoảng 18 s so với Phase 7: bộ Vitest từ vài chục lên 644 test và có `tsc -b` trên nhiều file hơn. Không có ngưỡng thời gian nào trong CI; các số này chỉ để ghi.

## 9.2 — Đường lưu trữ qua Istio (M-88)

**Một lỗi cấu hình tìm ra khi đọc trước khi chạy.** `base/kustomization.yaml` đặt `UNIHUB_OBJECT_STORAGE_PUBLIC_ENDPOINT=https://unihub.local`, còn máy này vào gateway bằng `kubectl port-forward` ở **8443** (`e2e.sh`, `check-mesh.sh`). Chữ ký SigV4 của URL PUT phủ Host kèm cổng, và trình duyệt gửi Host của chính trang, nên URL không cổng sẽ PUT vào cổng 443 (không có gì ở đó) và bước tải ảnh bìa của hành trình Organizer sẽ đỏ. Hai overlay `dev` và `loadtest` nay đặt `https://unihub.local:8443`; base không đổi; `KubernetesManifestsTest` có một test ghim giá trị đó.

`k8s/scripts/check-storage-edge.sh` (script mới) chạy qua gateway thật, **20 kiểm tra đều `ok`** trên cả hai image (`01caf83` và `bfc542d`):

- URL tải lên mang đúng nguồn `https://unihub.local:8443`.
- PUT bị từ chối (403) khi sai `Content-Type`, sai kích thước hoặc sai key; PUT đúng được nhận (200).
- Xác nhận trả `/media/covers/{workshopId}/….png`; chi tiết công khai mang cùng đường dẫn.
- GET ẩn danh của ảnh: 200, `image/png`, `X-Content-Type-Options: nosniff`, byte giống byte đã tải; HEAD 200.
- Phải đóng, trả 405: GET và DELETE trên đường dẫn bucket, gốc bucket, `/media/`, `/media/brochures/…`, một đường `covers/../brochures/…` gửi nguyên (`--path-as-is`), POST lên đường ảnh; không đường nào trả HTML.

Bước tải **brochure** thật đi qua gateway nằm trong `organizer-journey` của 9.3 (trình duyệt PUT một PDF); script này chỉ phủ ảnh bìa.

## 9.3 — `make smoke` và `make e2e` dưới STRICT

**`make smoke`:** xanh. Hai lần đầu đỏ vì lý do không thuộc ứng dụng, và đã sửa hoặc giải thích:

- Số target Prometheus tối thiểu là 4 (đúng với `loadtest`: 3 API + RabbitMQ), nên trên `dev` (1 API + RabbitMQ = 2 target) báo `FAIL` dù không target nào down. `smoke.sh` nay đòi `số replica của backend-api + 1`. Một target `down` ở lần chạy đầu là pod API cũ đang `Terminating`.
- Một `429` ở lần thứ ba là do chạy smoke liên tiếp trên cùng một Student (một danh tính một ngân sách, INV-17); chạy lại sau một phút thì xanh.
- Bước M-60 (replica Postgres) `skip` trên `dev`; M-60 là bằng chứng của Phase 7 trên `loadtest`.

**`make e2e`, 32 test (5 `transport` + 9 + 9 + 9 trên ba engine; ca phân trang chỉ chạy ở Chromium):**

| Lượt | Image | Kết quả |
|---|---|---|
| 1 | `01caf83` | 30 đạt, 2 bỏ qua (3,3 phút) |
| 2 | `01caf83` | **29 đạt, 1 đỏ**, 2 bỏ qua: `catalog.spec.ts` "diễn giả và địa điểm" trên Firefox |
| 3, 4, 5 | `bfc542d` | 30 đạt, 2 bỏ qua, mỗi lượt 3,3 phút |

**Ca đỏ ở lượt 2 là lỗi thật của ứng dụng, đã sửa.** Ảnh chụp trang lúc lỗi cho thấy ô "Diễn giả" rỗng trong khi bộ lọc đang áp dụng vẫn còn `Diễn giả "…"`. Nguyên nhân: `useDraft` áp mỗi ô lọc sau 500 ms; xóa ô "Diễn giả" rồi điền ô "Địa điểm" liền nhau (Playwright `fill`) làm hai bộ hẹn giờ nổ trong cùng một nhịp, và hai lần `update()` đều dựng patch từ cùng một bản `search` cũ của lần render, nên lần ghi thứ hai đặt lại `speaker=Lan` vừa bị xóa. `useDraft` không dựng lại timer vì `draft` và `value` không đổi, nên trạng thái kẹt: ô rỗng, URL còn bộ lọc. Đây cũng là ca đã đỏ một lần không rõ nguyên nhân ở 8.3 (trước đó nghi là giới hạn tốc độ; nghi ngờ đó **sai**). Sửa ở `use-catalog-search.ts` và `use-managed-search.ts` (cùng mẫu): `update()` đọc bản mới nhất qua một `ref` ghi đồng bộ. Test Vitest `keeps both edits when two text filters change inside one debounce window` dùng đồng hồ giả để nổ hai timer cùng lúc; **đỏ trước khi sửa** (`?speaker=Lan&location=B2` thay vì `?location=B2`), xanh sau (644 test Vitest).

Ba lượt liên tiếp trên `bfc542d` xanh không kèm retry (`retries: 0`). Rủi ro `429` đã nêu ở 9.3 không xảy ra ở cả năm lượt: 63 lời gọi dựng dữ liệu của test phân trang không trùng cửa sổ với các lượt tìm ẩn danh.

**Mỗi test duyệt có guard CSP** (`support/fixtures.ts`): không có báo cáo vi phạm nào, ngoại trừ ngoại lệ WebKit-`<select>` đã được owner duyệt ngày 2026-11-06, không mở rộng. Probe `transport` mới kiểm `GET /theme-init.js` qua gateway trên image CI thật: `200`, `javascript`, không phải HTML của SPA.

## Tài nguyên sau lần chạy cuối (đo bằng `kubectl top`)

Node 5 671 MiB trong 10 GiB (47%); `backend-api` 687 MiB trên 1 GiB; `rabbitmq` 264 MiB. Pod `backend-api` và `web` 0 lần khởi động lại; `redis`, `rabbitmq`, `minio`, `mailpit` và hai stub có `restarts=1` do cluster được dừng và khởi động lại ở đầu phiên, không phải do tải.

## Chưa chứng minh

- Brochure chỉ được PUT qua trình duyệt (`organizer-journey`), không có kiểm tra curl riêng cho các đường từ chối của brochure.
- Overlay `loadtest` (3 pod API, replica Postgres) **không** được chạy ở Phase 8; mọi kết quả trên là overlay `dev`.
- Ca phân trang (21 workshop) chỉ chạy ở Chromium (ngân sách giới hạn tốc độ của Organizer).
- Safari và Firefox thật không được thử; ba engine là Playwright WebKit, Firefox và Chromium.
- Các URL trong email dùng `UNIHUB_WEB_BASE_URL=https://unihub.local` (không cổng), nên trên máy này liên kết trong email không mở được qua port-forward. Không có test nào đi theo liên kết đó.
