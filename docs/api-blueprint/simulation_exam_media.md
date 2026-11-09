# Card 05 — Media cho thi thử mô phỏng

## Phạm vi API

Ba API native dưới `/api/v1/media/simulation-exam`, gọi từ Practice bằng access JWT hiện tại của học viên và ticket do Practice ký. Frontend gọi API phiên thi của Practice; không tự tạo ticket hoặc gọi ba API này. Response là DTO trực tiếp, `Cache-Control: no-store`.

| POST | Quyền access JWT | Kết quả |
| --- | --- | --- |
| `/eligibility` | `media:read`, `simulations:read`, `simulations:exam` | Kiểm tối đa16 video đã được nguồn công bố xác nhận, trả READY/ETag; không cấp URL |
| `/playback` | Cả ba quyền trên | URL MP4 của đúng session/position/playbackEventId được Practice xác nhận đang thực hiện |
| `/review-playback` | Cả ba quyền trên | URL xem lại của session đã COMPLETED, do Practice xác nhận |

Body chỉ có `{"ticket":"<Practice-signed-ticket>"}`, tối đa16.384 ký tự. Không nhận actor/media/session trực tiếp ngoài ticket. Role Admin, `media:manage`, `practice:exam` hoặc `simulations:learn` không thay thế quyền thi mô phỏng. Service kiểm lại ba quyền, không chỉ dựa vào controller.

Media không đọc database phiên Practice. **Practice chịu trách nhiệm** ownership, một phiên active, trạng thái CURRENT/COMPLETED và chỉ mint ticket cho vị trí được phép. Media kiểm chữ ký/domain, JWT subject, bank/manifest, session/position/nonce và asset. Practice không trả raw ticket cho frontend và không ghi ticket/URL/JWT vào log.

## Ticket protocol v1

Khóa domain là HMAC-SHA256 của context bên dưới, dùng root bytes từ secret access Identity theo decoder tương thích đang có; dùng kết quả32 byte làm key HS256. Giữ decoder Base64 → Base64URL → UTF-8, root ngắn32 byte được SHA-256 trước. Không dùng trực tiếp root để ký ticket.

| Loại | Context dẫn xuất key | Header typ | purpose |
| --- | --- | --- | --- |
| Eligibility | `dts-media:simulation-exam-eligibility:v1` | `dts-simulation-exam-eligibility+jwt` | `SIMULATION_EXAM_ELIGIBILITY` |
| Current | `dts-media:simulation-exam-playback:v1` | `dts-simulation-exam-playback+jwt` | `SIMULATION_EXAM_PLAYBACK` |
| Review | `dts-media:simulation-exam-review:v1` | `dts-simulation-exam-review+jwt` | `SIMULATION_EXAM_REVIEW` |

Claim chung, đầy đủ và không thêm field: `iss=dts-practice`, `aud=[dts-media]`, `sub=<UUID học viên>`, `iat`, `exp`, `jti=<UUID>`, `purpose`, `bankVersion`, `manifestSha256=<64 lowercase hex>`, `targetType=SIMULATION_VIDEO`. Issued-at không nằm tương lai, expiry còn hạn và sau issued-at; lifetime không quá60 giây. Practice nên dùng45 giây. Không hỗ trợ nén JWT hoặc dùng lẫn access/learning/catalogue/review ticket.

Eligibility thêm duy nhất `candidates`, mảng1–16 asset có mediaId duy nhất. Current/review thêm đúng `sessionId`, `position` (1–10), `playbackEventId`, `asset`, `objectEtag`.

Asset có đúng5 field: `mediaId` UUID; `fileName` tên MP4 nguồn, tối đa500 ký tự, không path/control; `sha256` 64 lowercase hex; `durationMs`, `videoTrackDurationMs` là số nguyên dương, không quá3.600.000. Giới hạn này là kiểm input kỹ thuật cho video, không phải thời lượng phiên thi. Thời lượng và scoring timeline phải lấy từ snapshot đã xác minh của Content Builder; Media không tự tính hoặc nhận scoring.

`objectEtag` là chuỗi opaque1–128 ký tự `[A-Za-z0-9_-]`, lấy từ eligibility của đúng object và lưu cùng snapshot vị trí. ETag không thay thế SHA-256 nguồn. Không đưa filename/SHA/ETag/ticket vào DTO player đang thi; Practice kiểm các binding nội bộ rồi trả DTO tối thiểu.

## Kiểm video và cấp URL

Media yêu cầu row active, READY, PRIVATE, mediaType VIDEO, MIME video/mp4, tên nguồn khớp asset. Target chỉ SIMULATION_VIDEO hoặc legacy NULL đã được ticket attestation xác nhận. Nếu có checksum metadata SHA-256, nó phải khớp nguồn.

Object key phải theo dạng do hệ thống tạo `uploads/YYYY/MM/DD/<mediaId>.mp4`, để signed URL không lộ tên/số tình huống gốc. File không đúng dạng bị đánh dấu `OPAQUE_PATH_REQUIRED`; không tự di chuyển/xóa object. Hai API luyện/thumbnail cũ không bị áp thêm quy tắc này.

Storage phải active, provider MINIO và private bucket policy qua guard hiện có. Kiểm stat: object tồn tại, size dương ít nhất16 byte, MIME đúng; size metadata dương phải khớp, legacy size0 được đối chiếu bằng size object thật. Đọc `Range: bytes=0-15` kèm If-Match ETag, yêu cầu Content-Range đúng và header MP4 `ftyp`.

Sau đó kiểm SHA-256 toàn object với nguồn đã công bố, đọc có giới hạn theo size stat và If-Match ETag. Proof thành công cache trong process10 phút, khóa gồm storage/object/ETag/size/SHA, tối đa256 entry; ETag/size/SHA đổi sẽ kiểm lại. Mỗi lần vẫn kiểm metadata, private policy, stat và Range. Cache không thay đổi database.

Eligibility trả theo thứ tự candidates: `{bankVersion,manifestSha256,items:[{mediaId,ready,durationMs,videoTrackDurationMs,objectEtag,reason}]}`. Item không ready có ETag null/omitted. Reason: READY, MISSING, NOT_READY, SCOPE_MISMATCH, ASSET_MISMATCH, OPAQUE_PATH_REQUIRED, OBJECT_MISMATCH, RANGE_UNAVAILABLE, VIDEO_FORMAT_INVALID, OBJECT_SHA_MISMATCH, OBJECT_MISSING, OBJECT_CHANGED. Storage/network/policy lỗi trả503 cho request; không giả thành thiếu video hay điểm0.

Current/review kiểm lại cùng asset và ETag đã lưu rồi ký GET URL300 giây bằng signer/guard hiện có. Response: sessionId, position, playbackEventId, bankVersion, manifestSha256, mediaId, url, expiredAt, mimeType, durationMs, usage CURRENT/REVIEW. Practice phải kiểm mọi binding trước khi dùng; không forward mediaId/manifest trong DTO tối thiểu nếu chưa cần. Gia hạn dùng ticket mới từ Practice; không cần tạo reaction/kết quả mới.

ETag được kiểm lúc cấp URL. URL đã cấp là bearer capability đến expiry; không hứa thu hồi tức thời sau cancel, và frontend không được lưu/log/chia sẻ URL. Object phải được giữ ổn định trong thời hạn URL; nếu object đổi, lần cấp lại bị chặn thay vì dùng video mới với timeline cũ.

## HTTP và kiểm thử

- 400: body/ticket field không đúng; không nhận thêm userId, mediaId hoặc điểm.
- 401: thiếu JWT, sai chữ ký/issuer, hết hạn.
- 403: thiếu quyền, ticket sai/hết hạn/domain khác/khác subject hoặc claims không hợp lệ.
- 409: video playback không READY, ngoài scope, thay asset hoặc ETag. Không cấp URL.
- 503: storage/policy/transport/signer không khả dụng. Không ghi trạng thái giả.

Error theo handler Media hiện có `{timestamp,status,error,message}`; xử lý HTTP trước, không phụ thuộc message để chấm điểm. [OpenAPI](simulation_exam_media.openapi.json) mô tả DTO HTTP và nullable.

Check chạy thêm verifier, API security, object Range/SHA/cache và PostgreSQL read-only tests, cùng toàn bộ184 test Media đã nghiệm thu. PostgreSQL dùng container riêng qua V7, không migration mới. Các service/protocol learning và catalogue cũ giữ nguyên; chỉ mở thêm ba matcher POST có quyền media:read, sau đó method/service kiểm thêm hai quyền mô phỏng.

Runtime dương của ticket mới phải đi qua Practice triển khai Card 05. Check/Apply Media chưa chứng minh phiên thi, chấm35/50, playback thật hoặc browser. Không tạo ticket runtime bằng cách đưa secret cho client. Kiểm HTTP Range và URL không ký bị từ chối trên dữ liệu thật ở bước tích hợp.

Nguồn SDK đã đối chiếu: [MinIO8.5.6 ObjectConditionalReadArgs](https://github.com/minio/minio-java/blob/8.5.6/api/src/main/java/io/minio/ObjectConditionalReadArgs.java), [GetObjectResponse](https://github.com/minio/minio-java/blob/8.5.6/api/src/main/java/io/minio/GetObjectResponse.java), [StatObjectResponse](https://github.com/minio/minio-java/blob/8.5.6/api/src/main/java/io/minio/StatObjectResponse.java).
