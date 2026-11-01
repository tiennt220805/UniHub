# Phase 5 — Kịch bản kiểm tra trên iPhone (Check-in Client)

> **Mục đích.** Jest chạy trên Node nên không chứng minh được bốn điều: app chạy dưới Hermes trong Expo Go, camera đọc được QR, dữ liệu sống sót khi app bị tắt, và điện thoại nói chuyện được với backend. Tài liệu này là kịch bản để Nguyễn Thành Tiến chạy tay **một lần** trên iPhone và điền kết quả. Nó là bằng chứng cho spike S1 và S2 (`checkin.md` §6; checklist 8.0, 8.3, 8.5, 8.6) và cho các dòng M-43, M-44, M-45 (nửa thiết bị).
> **Một ô trống là một khoảng trống, không phải một lần đạt.** Sign-off Phase 5 phải ghi rõ những phần nào đã điền.

## Cách đọc kịch bản

- Mỗi bước có **Bạn làm** (việc trên điện thoại hoặc lệnh), **Phải thấy** (kết quả đúng) và ô **Kết quả** (Đạt / Không đạt + ghi chú).
- Lệnh có dạng `! lệnh` là gõ vào ô chat của Claude Code (dấu `!` để chạy ngay trong phiên). Các lệnh dùng **đường dẫn đầy đủ** tới thư mục công cụ thử (gọi tắt là `PT` ở phần "Môi trường"), nên có thể chép nguyên dòng.
- Điện thoại và máy Mac phải **cùng mạng Wi-Fi** (địa chỉ Mac: `192.168.1.3`).
- Khi một bước cho kết quả khác "Phải thấy": **dừng lại**, chụp màn hình, ghi số bước và báo cho Claude. Đừng cố sửa.

### Cách quét QR từ màn hình máy tính

Mở trang QR trên Mac (đã mở sẵn; nếu mất: `! open /private/tmp/claude-501/-Users-tiennt-Desktop-projects-UniHub/afa7dc92-7da0-415f-aa60-5f40b1ddf918/scratchpad/phone/qr-page.html`). Đưa camera điện thoại cách màn hình khoảng 15–25 cm, đủ sáng, giữ yên một giây cho tới khi ô kết quả của app đổi. Mỗi mã có nhãn: **A-1, A-2, A-3, B-1, GIẢ, LẠ** (và thẻ **EXPO GO** để mở app).

Để quét **lại cùng một mã**: dời camera khỏi màn hình khoảng 3 giây rồi đưa lại. (App coi cùng một mã trong 2,5 giây là một lần quét, vì camera báo cùng một mã nhiều lần mỗi giây.)

## Môi trường của lần chạy

| | |
|---|---|
| Ngày / người chạy | |
| Điện thoại, phiên bản iOS | |
| Phiên bản Expo Go (Expo Go → Settings) và SDK nó chạy | (cần SDK 58) |
| Backend | `bootRun` tại Mac, `http://192.168.1.3:8080`, khóa ký **cố định** (vé không chết khi khởi động lại backend) |
| Expo | `exp://192.168.1.3:8081`, app đọc `EXPO_PUBLIC_API_BASE_URL=http://192.168.1.3:8080` |
| Công cụ (`PT`) | `/private/tmp/claude-501/-Users-tiennt-Desktop-projects-UniHub/afa7dc92-7da0-415f-aa60-5f40b1ddf918/scratchpad/phone` |

Các script trong `PT`: `status.sh` (backend, Expo, docker có đang chạy không), `backend-stop.sh` và `backend-start.sh` (tắt/bật backend để giả lập mất kết nối), `results.sh` (xem **server** đang giữ những gì sau khi điện thoại đồng bộ).

**Tài khoản** (mật khẩu chung `DevPass-12345`): `staff1@dev.unihub.local`, `staff2@dev.unihub.local` (nhân viên check-in), `student1@dev.unihub.local` (sinh viên, dùng để kiểm tra bị từ chối).

**Hai phòng thử:** "Check-in Test A" (Phòng A101) và "Check-in Test B" (Phòng B202). Danh sách phòng của app còn có thể có thêm workshop "Kỹ năng thuyết trình" từ các lần thử Web trước đó; bỏ qua nó.

**Sáu mã QR trên trang QR và ý nghĩa của chúng** (bạn không cần nhớ, bảng dưới nhắc lại ở từng bước):

| Nhãn | Là gì | Ghi chú |
|---|---|---|
| A-1 | Vé thật, đăng ký còn hiệu lực, phòng A | |
| A-2 | Vé thật, đăng ký còn hiệu lực, phòng A | Sẽ quét hai lần |
| A-3 | Vé thật, phòng A, **nhưng đăng ký đã bị hủy** trên server | Điện thoại không thể biết là đã hủy |
| B-1 | Vé thật, đăng ký còn hiệu lực, **phòng B** | Quét khi đang ở phòng A |
| GIẢ | Mã có đúng hình dạng vé nhưng ký bằng khóa lạ | |
| LẠ | Mã QR bất kỳ không phải của UniHub | |

---

## Phần 0 — Kiểm tra trước khi bắt đầu (1 phút)

| # | Bạn làm | Phải thấy | Kết quả |
|---|---|---|---|
| 0.1 | `! /private/tmp/claude-501/-Users-tiennt-Desktop-projects-UniHub/afa7dc92-7da0-415f-aa60-5f40b1ddf918/scratchpad/phone/status.sh` | Ba dòng: `backend: 200`, `expo: 200`, và danh sách 4 container docker (`unihub-postgres`, `unihub-redis`, `unihub-rabbitmq`, `unihub-mailpit`) | |
| 0.2 | Trên iPhone cài **Expo Go** từ App Store (nếu chưa có). Mở Settings của Expo Go xem phiên bản | Đọc được phiên bản và SDK (ghi vào bảng Môi trường) | |
| 0.3 | iPhone nối cùng Wi-Fi với Mac | Mở Safari trên iPhone, vào `http://192.168.1.3:8080/api/v1/workshops?size=1`: thấy một đoạn JSON (không phải lỗi không kết nối) | |

Nếu 0.3 không mở được: Wi-Fi khác mạng, hoặc mạng chặn thiết bị với nhau (Wi-Fi công cộng, "AP isolation"). Báo cho Claude, đừng đi tiếp.

## Phần 1 — Mở app, đăng nhập, chọn phòng (cần mạng)

| # | Bạn làm | Phải thấy | Kết quả |
|---|---|---|---|
| 1.1 | Quét thẻ **EXPO GO** trên trang QR bằng camera iPhone, bấm thông báo mở trong Expo Go | Expo Go hiện thanh tiến trình "Bundling" (lần đầu 30–90 giây), rồi app hiện màn **Đăng nhập** với tiêu đề "UniHub Check-in" | |
| 1.2 | Đăng nhập bằng `student1@dev.unihub.local` | Hiện câu đỏ **"Tài khoản này không phải nhân viên check-in."**, vẫn ở màn đăng nhập | |
| 1.3 | Đăng nhập sai mật khẩu với `staff1@dev.unihub.local` | **"Email hoặc mật khẩu không đúng."** | |
| 1.4 | Đăng nhập đúng `staff1@dev.unihub.local` / `DevPass-12345` | Màn **quét**: tiêu đề phòng ghi **"Chưa chọn phòng"**, có khung vàng "Hãy chọn phòng (workshop) đang check-in trước khi quét.", **không có khung camera** | |
| 1.5 | Bấm **Chọn phòng** | Danh sách có **Check-in Test A** (Phòng A101) và **Check-in Test B** (Phòng B202) | |
| 1.6 | Chọn **Check-in Test A** | Quay lại màn quét; đầu màn ghi **"Check-in Test A · Phòng A101"** | |
| 1.7 | iOS hỏi quyền camera: chọn **Cho phép** (hoặc bấm "Cho phép camera") | Khung camera hiện ra | |
| 1.8 | Nhìn phần dưới màn hình | Ghi **"0 lần quét chờ gửi"**, nút "Đồng bộ ngay", liên kết "Lịch sử quét", nút "Đăng xuất" | |

**Điều bước 1.4 chứng minh:** app đã tải được khóa xác minh (nếu không có khóa, màn quét sẽ ghi "Chưa có khóa xác minh nên chưa thể quét"). Nếu thấy dòng đó, ghi lại và báo.

## Phần 2 — Quét khi mất kết nối với máy chủ (spike S1, M-43, M-44)

| # | Bạn làm | Phải thấy | Kết quả |
|---|---|---|---|
| 2.0 | Tắt backend: `! /private/tmp/claude-501/-Users-tiennt-Desktop-projects-UniHub/afa7dc92-7da0-415f-aa60-5f40b1ddf918/scratchpad/phone/backend-stop.sh` | `backend STOPPED`. (Điện thoại vẫn giữ Wi-Fi để app vẫn nối với Expo) | |

Quét lần lượt, **đọc kết quả sau mỗi mã** (thẻ kết quả hiện dưới khung camera và giữ nguyên tới lần quét sau):

| # | Quét | Phải thấy | Đếm "chờ gửi" | Kết quả |
|---|---|---|---|---|
| 2.1 | **A-1** | Xanh: **"Hợp lệ (chờ đối soát) · 1JNFTKN8…"** (mã 26 ký tự, bắt đầu bằng 8 ký tự giống mã ghi ở bảng trang QR), **không có cảnh báo vàng** | 1 | |
| 2.2 | **A-2** | Xanh "Hợp lệ (chờ đối soát) · …", không cảnh báo | 2 | |
| 2.3 | **A-2 lần nữa** (dời camera ra ≥3 giây rồi đưa lại) | Xanh "Hợp lệ…" **kèm khung vàng "Vé này đã quét lúc hh:mm."** (giờ của lần quét 2.2) | 3 | |
| 2.4 | **A-3** | Xanh "Hợp lệ…" (chữ ký đúng; điện thoại không biết đăng ký đã hủy), không cảnh báo | 4 | |
| 2.5 | **B-1** | Xanh "Hợp lệ…" **kèm khung vàng "Vé của workshop khác với phòng đang chọn."** | 5 | |

**Bây giờ bật chế độ máy bay** (kéo Control Center xuống, bấm biểu tượng máy bay; Wi-Fi tắt hoàn toàn). Expo Go vẫn đang chạy app, không cần đóng.

| # | Quét | Phải thấy | Đếm | Kết quả |
|---|---|---|---|---|
| 2.6 | **GIẢ** | Đỏ: **"Vé không hợp lệ. Chữ ký không đúng: vé giả, bị sửa, hoặc đã bị thay bằng khóa mới."** | 6 | |
| 2.7 | **LẠ** | Đỏ: **"Vé không hợp lệ. Đây không phải mã vé của UniHub."** | 7 | |
| 2.8 | Bấm **Lịch sử quét** | 7 dòng, mới nhất ở trên: `Vé không hợp lệ` (2 dòng), 5 dòng có mã vé. Mọi dòng ghi **"Chờ gửi"**. Dòng B-1 có nhãn **"Sai phòng"**, dòng A-2 (lần hai) có nhãn **"Quét lặp"**. Không dòng nào hiện nội dung của mã giả | 7 | |
| 2.9 | Quay lại màn quét, bấm **Đồng bộ ngay** (vẫn đang bật máy bay) | Dòng **"Chưa gửi được (không kết nối được máy chủ). Các lần quét vẫn được giữ trên máy."** Số chờ gửi vẫn **7** | 7 | |
| 2.10 | **Tắt chế độ máy bay** (Wi-Fi về lại; backend vẫn đang tắt) | Trong vài giây, dòng trạng thái đồng bộ vẫn là "Chưa gửi được…" (app tự thử khi mạng về, vẫn thất bại vì backend tắt). Số chờ gửi vẫn **7** | 7 | |

**Điều Phần 2 chứng minh:** vé được xác minh hoàn toàn offline (không cần máy chủ), vé giả/lạ bị nhận ra ngay, mọi lần quét được ghi lại trước khi hiện kết quả, đồng bộ lỗi không làm mất scan.

> Nếu 2.1 cho "Vé không hợp lệ" với một vé thật: **đây là kết quả quan trọng nhất cần báo** (nghĩa là bộ xác minh không chạy đúng dưới Hermes trên điện thoại). Ghi lại dòng lý do hiện ra.

## Phần 3 — App bị tắt rồi mở lại (spike S2, M-44)

| # | Bạn làm | Phải thấy | Kết quả |
|---|---|---|---|
| 3.1 | **Tắt hẳn Expo Go**: vuốt lên từ mép dưới (hoặc bấm đúp nút Home) để mở danh sách app, vuốt Expo Go lên để đóng | Expo Go biến mất khỏi danh sách | |
| 3.2 | Mở lại Expo Go, vào mục **Recently opened** (hoặc quét lại thẻ EXPO GO) và mở "UniHub Check-in" | App mở thẳng vào **màn quét** (không phải màn đăng nhập), vẫn ghi **"Check-in Test A · Phòng A101"** | |
| 3.3 | Nhìn số chờ gửi | **"7 lần quét chờ gửi"** | |
| 3.4 | Mở **Lịch sử quét** | Vẫn đủ **7 dòng** giống 2.8 (cùng mã, cùng nhãn "Sai phòng"/"Quét lặp") | |
| 3.5 | Quét **A-2** thêm một lần nữa | Xanh "Hợp lệ…" kèm **"Vé này đã quét lúc hh:mm."** với giờ của **lần quét trước khi tắt app** → chứng minh lịch sử quét còn nguyên sau khi app bị tắt. Số chờ gửi: **8** | |

> Ghi chú: Từ bước này thêm một lần quét A-2 (lần thứ ba). Các kết quả server ở Phần 4 tính cả lần này (bảng bên dưới đã tính).

## Phần 4 — Có lại máy chủ: đồng bộ (M-45, nửa thiết bị)

| # | Bạn làm | Phải thấy | Kết quả |
|---|---|---|---|
| 4.1 | Bật backend: `! /private/tmp/claude-501/-Users-tiennt-Desktop-projects-UniHub/afa7dc92-7da0-415f-aa60-5f40b1ddf918/scratchpad/phone/backend-start.sh` (chờ khoảng 30–60 giây) | `backend UP (http://192.168.1.3:8080)` | |
| 4.2 | Trong app, bấm **Đồng bộ ngay** (hoặc đợi tối đa 1 phút, app tự gửi) | Dòng **"Đã gửi 8 lần quét lên máy chủ."**, số chờ gửi về **0** | |
| 4.3 | Mở **Lịch sử quét** | 8 dòng, **không còn dòng nào "Chờ gửi"**. Mỗi dòng ghi **"Đã gửi · …"** theo bảng dưới | |
| 4.4 | Báo cho Claude: "xong phần 4" | Claude chạy `results.sh` đối chiếu cơ sở dữ liệu (Phần 5) | |

**Kết quả đúng của từng lần quét trong lịch sử (theo thứ tự quét, 1 → 8):**

| Lần | Mã | Hiển thị trong lịch sử |
|---|---|---|
| 1 | A-1 | Đã gửi · Được chấp nhận |
| 2 | A-2 (lần 1) | Đã gửi · Được chấp nhận |
| 3 | A-2 (lần 2) | Đã gửi · Trùng: đã có lần quét sớm hơn |
| 4 | A-3 (đăng ký đã hủy) | Đã gửi · Không hợp lệ (server) |
| 5 | B-1 (sai phòng) | Đã gửi · Không hợp lệ (server) |
| 6 | GIẢ | Đã gửi · Không hợp lệ (server) |
| 7 | LẠ | Đã gửi · Không hợp lệ (server) |
| 8 | A-2 (lần 3, sau khi tắt app) | Đã gửi · Trùng: đã có lần quét sớm hơn |

## Phần 5 — Đối chiếu cơ sở dữ liệu (Claude làm, bạn chỉ báo "xong phần 4")

Claude chạy `/private/tmp/claude-501/-Users-tiennt-Desktop-projects-UniHub/afa7dc92-7da0-415f-aa60-5f40b1ddf918/scratchpad/phone/results.sh` và kiểm:

| Kiểm | Kỳ vọng |
|---|---|
| Số dòng `check_in_scans` | 8, tất cả `scanned_by` = **Dev Check-in Staff 1**, cùng một `device_id` (UUID của điện thoại) |
| `outcome` | `ACCEPTED` ×2 (A-1, A-2 lần 1), `REJECTED_DUPLICATE` ×2 (A-2 lần 2 và 3), `REJECTED_INVALID` ×4 (A-3, B-1, GIẢ, LẠ) |
| GIẢ và LẠ | `REJECTED_INVALID` **không gắn đăng ký nào** (cột ticket ghi "(no ticket)"): server ghi lại cả vé giả |
| Bảng `attendances` | Đúng **2 dòng**: A-1 và A-2, mỗi vé một dòng, dù A-2 được quét 3 lần |
| `scanned_at` | Là giờ **lúc quét trên điện thoại** (không phải lúc đồng bộ) |

## Phần 6 — Hai nhân viên dùng chung một điện thoại (D53)

Mục tiêu: scan của nhân viên 1 không bao giờ bị gửi dưới tên nhân viên 2, và không mất.

| # | Bạn làm | Phải thấy | Kết quả |
|---|---|---|---|
| 6.1 | `! /private/tmp/claude-501/-Users-tiennt-Desktop-projects-UniHub/afa7dc92-7da0-415f-aa60-5f40b1ddf918/scratchpad/phone/backend-stop.sh`. Trong app (vẫn là staff1, phòng A) quét **A-1** | Xanh "Hợp lệ…" kèm "Vé này đã quét lúc hh:mm." Số chờ gửi: **1** | |
| 6.2 | Bấm **Đăng xuất** | Hiện khung vàng **"Còn 1 lần quét chưa gửi. Chúng được giữ trên máy và chỉ gửi khi bạn đăng nhập lại."** với hai nút "Vẫn đăng xuất" và "Hủy" | |
| 6.3 | Bấm **Hủy** | Vẫn ở màn quét, vẫn đăng nhập | |
| 6.4 | Bấm **Đăng xuất** rồi **Vẫn đăng xuất** | Về màn **Đăng nhập** | |
| 6.5 | (Backend vẫn tắt) đăng nhập `staff1@dev.unihub.local` | Câu đỏ **"Không kết nối được máy chủ (http://192.168.1.3:8080). Kiểm tra mạng rồi thử lại."** | |
| 6.6 | `! /private/tmp/claude-501/-Users-tiennt-Desktop-projects-UniHub/afa7dc92-7da0-415f-aa60-5f40b1ddf918/scratchpad/phone/backend-start.sh`, chờ "backend UP". Đăng nhập **`staff2@dev.unihub.local`** | Màn quét. Số chờ gửi: **"0 lần quét chờ gửi · 1 của người khác"** | |
| 6.7 | Bấm **Đồng bộ ngay** | Dòng **"Không có lần quét nào cần gửi."** (scan của staff1 **không** bị gửi) | |
| 6.8 | Mở **Lịch sử quét** | Có dòng của staff1 ghi **"Chờ gửi · của nhân viên khác (gửi khi họ đăng nhập)"** | |
| 6.9 | Claude chạy `results.sh` (bạn báo "xong 6.8") | Vẫn 8 dòng (chưa có scan nào của lần 6.1) | |
| 6.10 | Quét **A-2** bằng staff2 (nếu app báo chưa chọn phòng: chọn lại "Check-in Test A") rồi bấm **Đồng bộ ngay** | **"Đã gửi 1 lần quét lên máy chủ."**; số chờ gửi: **"0 lần quét chờ gửi · 1 của người khác"** | |
| 6.11 | **Đăng xuất** (không có scan riêng nên đăng xuất ngay), đăng nhập lại **`staff1`** | Màn quét; trong vòng 1 phút (hoặc bấm "Đồng bộ ngay") **"Đã gửi 1 lần quét"**, về **0** chờ gửi | |
| 6.12 | Báo "xong phần 6", Claude chạy `results.sh` | **10** dòng. Dòng mới: một của **Dev Check-in Staff 2** (A-2, `REJECTED_DUPLICATE`) và một của **Dev Check-in Staff 1** (A-1, `REJECTED_DUPLICATE`), mỗi dòng đúng người quét. `attendances` vẫn 2 dòng | |

## Phần 7 — Phiên đăng nhập hết hạn (tùy chọn, mất khoảng 35 phút chờ)

Token đăng nhập sống 30 phút và không có refresh. Điều cần thấy: hết hạn **không** làm mất scan và **không** chặn việc quét offline.

| # | Bạn làm | Phải thấy | Kết quả |
|---|---|---|---|
| 7.1 | Ghi giờ đăng nhập `staff1` ở 6.11. Chờ **hơn 30 phút** (có thể để app mở hoặc tắt hẳn Expo Go rồi mở lại) | Sau khi mở lại: **vẫn ở màn quét**, không bị đăng xuất | |
| 7.2 | Quét **A-1** (backend đang bật) | Xanh "Hợp lệ…", số chờ gửi 1 | |
| 7.3 | Đợi tối đa 1 phút (app tự thử đồng bộ) | App chuyển về màn **Đăng nhập** với khung vàng **"Phiên đăng nhập đã hết hạn. Các lần quét chưa gửi vẫn được giữ trên máy."** | |
| 7.4 | Đăng nhập lại `staff1` | Số chờ gửi **1** rồi tự đồng bộ về 0; `results.sh` có thêm 1 dòng `REJECTED_DUPLICATE` của staff1 | |

## Phần 8 — Báo kết quả cho Claude

Trả lời theo mẫu (ngắn là đủ):

```
Phần 0: đạt / không đạt (ghi bước lỗi)
Phần 1: ...
Phần 2: 2.1 ... (nếu khác "Phải thấy", ghi dòng chữ app hiện)
Phần 3: ...
Phần 4: xong
Phần 6: ...
Phần 7: bỏ qua / đạt / ...
Điện thoại: iPhone ..., iOS ..., Expo Go ...
```

Gửi kèm ảnh chụp màn hình cho bất kỳ bước nào không đạt.

## Xử lý sự cố nhanh

| Hiện tượng | Nguyên nhân thường gặp | Làm gì |
|---|---|---|
| Expo Go: "Could not connect to the server" | iPhone không cùng mạng với Mac, hoặc Expo chưa chạy | `! /private/tmp/claude-501/-Users-tiennt-Desktop-projects-UniHub/afa7dc92-7da0-415f-aa60-5f40b1ddf918/scratchpad/phone/status.sh` (expo phải `200`); kiểm tra Wi-Fi |
| App mở nhưng báo lỗi đỏ ngay | Lỗi trong bundle | Chụp màn hình lỗi, gửi Claude |
| "Không kết nối được máy chủ" khi backend đang bật | `http` tới địa chỉ LAN bị chặn, hoặc Wi-Fi khác mạng | Mở Safari tới `http://192.168.1.3:8080/api/v1/workshops?size=1` (xem 0.3) |
| Camera không bắt mã | Thiếu sáng, quá xa/gần, màn hình mờ | Tăng độ sáng màn hình Mac, thử cách 15–25 cm; giữ yên 1–2 giây |
| Vé thật báo "Vé không hợp lệ" | Backend đã khởi động lại với khóa khác (vé cũ chết) hoặc lỗi xác minh | Báo Claude ngay (kèm lý do app hiện) |
| Đăng nhập lỗi 500 | Backend chưa lên hẳn | Chờ `backend UP` rồi thử lại |

## Những gì kịch bản này không bao phủ

- Android: không thử.
- Bản dựng độc lập (EAS preview, TestFlight): không thuộc Phase 5.
- Chế độ máy bay thật + tắt app + mở lại: không làm được, vì Expo Go cần máy chủ phát triển (Metro) để mở lại app. Vì thế phần "tắt app" (Phần 3) dùng cách tắt **backend** chứ không tắt Wi-Fi; "không có mạng thật" được kiểm riêng ở 2.6–2.10 (app đang mở).
- Hết hạn token sau khi tắt backend: đã có test tự động, không lặp lại tay.
