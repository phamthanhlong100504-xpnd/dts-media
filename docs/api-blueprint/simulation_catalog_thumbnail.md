# Thumbnail danh sách tình huống mô phỏng

## Part 0 — Phạm vi và quyền

`POST /api/v1/media/simulation-catalog-thumbnail` trả URL đọc thumbnail trước khi học viên tạo lượt luyện tập. Practice lấy binding từ snapshot ngân hàng đã PUBLISHED của Content Builder, kiểm tra quyền truy cập ngân hàng rồi cấp ticket. Media kiểm tra ticket và metadata trước khi ký URL MinIO.

- Bearer access JWT hợp lệ của học viên; quyền `media:read` và `simulations:read` được kiểm tra ở filter/controller và service.
- Không yêu cầu quyền quản trị hoặc quyền upload. Không tạo attempt, không ghi tiến độ.
- Ticket catalogue dùng riêng cho thumbnail; video và thumbnail của lượt luyện tập tiếp tục dùng [simulation-playback](simulation_media_access.md).

## Part 1 — Request và response

```http
POST /api/v1/media/simulation-catalog-thumbnail
Authorization: Bearer <access-token>
Content-Type: application/json

{"ticket":"<short-lived-ticket-from-practice>"}
```

HTTP 200 trả DTO trực tiếp, không bọc `data`:

```json
{
  "mediaId": "00000000-0000-4000-8000-000000000003",
  "scenarioId": "00000000-0000-4000-8000-000000000004",
  "bankVersion": "dts-simulation-120-card01-v1",
  "url": "<presigned-thumbnail-url>",
  "expiredAt": "2026-11-02T00:00:00Z"
}
```

Response có `Cache-Control: no-store`. URL có thời hạn 300 giây. Khi hết hạn, Practice kiểm tra lại binding và lấy URL mới; không lưu URL hoặc ticket vào snapshot ngân hàng. Practice gọi Media bằng access JWT của chính học viên và ticket phía server, rồi map URL/expiry vào DTO giao diện; không cần đưa ticket cho trình duyệt.

## Part 2 — Contract ticket giữa Practice và Media

| Trường | Điều kiện |
| --- | --- |
| Chữ ký | HS256; key = HMAC-SHA256(access-secret-bytes, UTF-8 `dts-media:simulation-catalog-thumbnail:v1`) |
| Secret bytes | Giải mã như `MediaJwtVerifier`: Base64, fallback Base64URL, fallback UTF-8; nếu dưới 32 byte dùng SHA-256 trước khi derive key |
| Header `typ` | `dts-simulation-catalog+jwt` |
| `iss` / `aud` | `dts-practice` / chỉ `dts-media` |
| `purpose` | `SIMULATION_CATALOG_THUMBNAIL` |
| `sub` | UUID người dùng, trùng subject access JWT |
| `jti` | UUID chuẩn, tạo cho mỗi ticket |
| `iat` / `exp` | Bắt buộc, `iat` không ở tương lai, chưa hết hạn, `0 < exp-iat <= 60` giây |
| `mediaId` / `scenarioId` | UUID chuẩn, đúng cặp thumbnail/tình huống trong snapshot PUBLISHED mà Practice đã đọc từ Content Builder |
| `bankVersion` | 1–96 ký tự, `[A-Za-z0-9][A-Za-z0-9._-]{0,95}` |
| `targetType` | Chỉ `SIMULATION_THUMBNAIL` |
| `attemptId` | Không có; catalogue không đại diện cho một lượt luyện tập |

Media không truy vấn Content Builder cho từng ảnh; Practice chịu trách nhiệm xác minh binding trước khi ký. Không nhận mediaId tùy ý từ frontend để ký ticket. Media xác minh chữ ký, subject và scope; metadata phải còn active, READY, PRIVATE, mediaType IMAGE, tên `.webp`, MIME `image/webp`, uploadTargetType `SIMULATION_THUMBNAIL` hoặc NULL của dữ liệu cũ được Practice attestation. Storage dùng lại kiểm tra private bucket hiện hành. Ticket có thể dùng lại trong thời hạn để đọc cùng ảnh; đây không phải token dùng một lần. Không dùng `jti` như cơ chế thu hồi hay chống replay.

## Part 3 — Lỗi và kiểm thử

| HTTP | Trường hợp |
| --- | --- |
| 400 | Body không hợp lệ; metadata tên/MIME không khớp thumbnail; media không tồn tại hoặc đã xóa theo contract `findActive` hiện hành |
| 401 | Thiếu hoặc sai access JWT |
| 403 | Thiếu một trong hai quyền; ticket sai/hết hạn/khác subject; scope video; metadata không PRIVATE/IMAGE hoặc sai target |
| 409 | Media chưa READY |
| 503 | Storage không sẵn sàng, bucket không bảo đảm private hoặc không ký được URL |

`SimulationCatalogTicketVerifierTest` kiểm tra claim bắt buộc, thời hạn, subject, scope và việc tách key với access/playback. `SimulationCatalogSecurityTest` chạy qua Spring Security và service, kiểm tra cả quyền, legacy NULL target và không ký URL khi metadata bị từ chối. Bộ kiểm thử Media trước đó tiếp tục chạy để kiểm tra hồi quy.

Thay đổi này không có SQL hoặc cấu hình bucket mới. Nghiệm thu end-to-end cần Practice phát hành ticket từ ngân hàng thật, học viên đọc thumbnail trước attempt và trình duyệt tải ảnh; kiểm thử Media riêng chưa chứng minh luồng đó.
