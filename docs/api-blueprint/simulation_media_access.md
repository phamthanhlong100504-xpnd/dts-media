# Media — quyền truy cập và video mô phỏng

## Part 0 — Phạm vi

GAP Card 01 trên feat/simulation-media-access, base 2bd9930. Kế thừa policy V6; Identity cần V12 và JWT mới. Media giữ DTO/Page trực tiếp; Practice quản lý lượt luyện/chấm/tiến độ. Frontend học viên gọi Practice để lấy/gia hạn playback, không tự tạo ticket hoặc xác nhận hoàn thành qua Media.

## Part 1 — API và quyền

| API | Điều kiện | Kết quả |
| --- | --- | --- |
| GET /api/v1/media/{mediaId} | PUBLIC + READY + không phải target mô phỏng: giữ public; trường hợp khác cần media:read và đúng chủ hoặc ROLE_ADMIN + media:manage | MediaResponse; chủ/admin có thể đọc UPLOADING/FAILED với url=null |
| GET /api/v1/media | media:read; mặc định chỉ createdBy=JWT sub; ADMIN + media:manage được lọc toàn kho | Page; page từ 0, size 1–100, createdAt/id giảm dần; PRIVATE không phát URL trong list |
| POST /api/v1/media/uploads/initialize | media:upload; USER thuộc cá nhân; mọi target khác cần thêm ROLE_ADMIN + media:manage | 201, shape initialize giữ nguyên |
| POST /api/v1/media/uploads/{sessionId}/confirm | media:upload, đúng uploader của session và media; target chung mới cần quyền quản trị | 202; lặp UPLOADING/COMPLETED trả cùng receipt, không phát event mới |
| PATCH /api/v1/media/{mediaId} | media:update và đúng chủ hoặc ROLE_ADMIN + media:manage | 200 metadata, url=null; target mô phỏng không được đổi PUBLIC |
| DELETE /api/v1/media/{mediaId} | media:delete và đúng chủ hoặc ROLE_ADMIN + media:manage | 204 soft-delete metadata, không xóa object |
| POST /api/v1/media/simulation-playback | media:read + simulations:read, Bearer hợp lệ và ticket đúng chủ/phạm vi do Practice cấp | 200 với mediaId, url, expiredAt, bankVersion; URL 300 giây |

Media xác minh chữ ký/hạn/issuer/sub của access JWT bằng cùng cách giải mã khóa của Identity. Không chấp nhận refresh JWT, payload JWT chưa ký, X-User-* hoặc query userId làm danh tính. Query userId của PATCH/DELETE chỉ được giữ như hint bằng sub; bỏ query không mở ownership. List cho phép ADMIN manager chọn owner khác; người dùng khác không được làm vậy. Role ADMIN một mình không thay thế action permission.

Lỗi xác thực 401; thiếu quyền/ownership/ticket 403; dữ liệu upload sai 400; confirm trạng thái terminal hoặc playback chưa READY 409; URL/storage không sẵn sàng 503. Missing/deleted media/session giữ 400 như contract cũ. Lỗi binding/validation dùng Spring. Response lỗi không có wrapper success/data. Metadata trả Cache-Control: no-store; không log JWT, ticket hoặc URL đã ký.

## Part 2 — Upload và phát video

Initialize kiểm policy active/enabled, size dương/giới hạn, allow-list và block-list MIME theo phần tử JSON chính xác, visibility khớp policy, storage active. Simulation MP4/video/mp4 tối đa 2 GiB; thumbnail WebP/image/webp tối đa 20 MiB, cả hai PRIVATE. Kiểm extension và tên tệp; không cho học viên mượn media:upload để chọn target mô phỏng. V7 lưu upload_target_type cho tệp mới và seed storage private-minio/bucket media-private. Upload PRIVATE mới dùng storage này, PUBLIC tiếp tục default storage. Compose bổ sung bucket mới ở anonymous none; không đổi bucket media cũ.

Confirm khóa hàng session trong transaction, kiểm chủ trước khi đổi trạng thái. AFTER_COMMIT gửi Kafka như trước. Worker chỉ xử lý đúng cặp session/media ở UPLOADING, kiểm size và Content-Type bổ sung cho target mô phỏng. Không đồng nghĩa đã kiểm codec/file magic. Không có durable outbox hoặc bảo đảm recovery mới trong bản này.

Practice chỉ tạo ticket sau khi kiểm JWT sub, quyền mô phỏng, ownership lượt luyện và media thuộc snapshot ngân hàng hợp lệ. Gia hạn URL không tạo lượt luyện mới hoặc đổi response/score/progress. Content Builder phải kiểm media READY/PRIVATE và đúng định dạng khi publish; UUID chưa sẵn sàng không được công bố cho học viên. URL MinIO đã cấp có thể dùng tới khi hết hạn; xóa metadata/thu hồi JWT không thu hồi URL ngay lập tức.

## Part 3 — Contract ticket nội bộ cho Practice

Request Media: `POST /api/v1/media/simulation-playback`, Bearer access JWT của người dùng, JSON `{"ticket":"<server-signed-ticket>"}`. Practice gọi server-to-server và trả URL cho frontend. Ticket không đi trong query string.

Ticket là JWS HS256. Khóa: giải mã JWT_ACCESS_SECRET giống Identity (Base64, thử Base64URL nếu lỗi, cuối cùng UTF-8; dưới 32 byte thì SHA256). Sau đó `playbackKey = HMAC-SHA256(decodedAccessKey, UTF8("dts-media:simulation-playback:v1"))`. Chỉ server giữ khóa; không triển khai thuật toán ký trong frontend. Domain khóa riêng giúp ticket không dùng được làm access JWT.

| Claim | Bắt buộc |
| --- | --- |
| iss / aud | dts-practice / tập chỉ gồm dts-media |
| sub | UUID chuẩn của chủ lượt luyện, bằng Bearer sub |
| purpose | SIMULATION_LEARNING |
| iat / exp | NumericDate UTC; đã phát hành, chưa hết hạn, exp−iat tối đa 120 giây |
| attemptId / mediaId | UUID chuẩn; Practice kiểm quan hệ thật trước khi ký |
| bankVersion | Phiên bản snapshot, 1–100 ký tự chữ/số/dấu . _ : - |
| targetType | SIMULATION_VIDEO hoặc SIMULATION_THUMBNAIL |

Media xác minh ticket, subject, READY/PRIVATE, MIME/extension và target. Media mới phải khớp target đã lưu. Media cũ target NULL chỉ được mở qua ticket do Practice attested cho snapshot; không suy diễn target hoặc quyền từ đuôi file. Media không sở hữu bảng attempt/ngân hàng nên không tự xác minh quan hệ đó trong database Media: trách nhiệm ký đúng phạm vi thuộc Practice.

Response trực tiếp: mediaId (UUID), url (chuỗi), expiredAt (ISO UTC), bankVersion (chuỗi). Frontend cần buffer gia hạn trước hết hạn và xử lý lỗi video riêng; không dùng lỗi tải/URL hết hạn làm kết quả 0 điểm. Bucket MinIO PRIVATE và CORS/Range đúng môi trường vẫn phải nghiệm thu runtime; visibility trong database không tự đổi bucket policy.

## Part 4 — Migration và nghiệm thu

[V7](../../src/main/resources/db/migration/V7__add_simulation_media_scope.sql) thêm một cột nullable/CHECK và một storage private riêng. Giữ toàn bộ V1–V6 và hàng dữ liệu cũ: objectKey, bucket/ID, metadata, visibility/status. SQL không tạo bucket hoặc sao chép object. Không backfill bằng cách đoán từ MIME/tên file. File V7 phải có trước khi chạy Flyway; Hibernate dùng validate.

Candidate gồm unit hồi quy, JWT thật, MockMvc qua security filter và service thật với MinIO giả lập, nâng V6→V7/database trống/idempotency, cùng JPA/ownership/confirm trên PostgreSQL riêng. Không dùng kết quả đó để tuyên bố MinIO/Gateway/web đã đạt. Sau source apply/verify mới migrate và chạy Media bằng cấu hình local có JWT_ACCESS_SECRET của Identity. Sau các gói Content Builder/Practice mới nghiệm thu playback/Range/gia hạn và toàn luồng Card 01 trên web.


## Điều kiện MinIO khi triển khai

Source compose cũ đặt bucket media public. Không coi visibility PRIVATE trong database là đủ. Media kiểm bucket policy bằng internalMinioClient trước khi phát URL/PUT PRIVATE; policy anonymous wildcard hoặc không xác minh được trả503, không cấp URL. Credential backend cần đọc được bucket policy; NoSuchBucketPolicy từ MinIO được nhận là bucket không có anonymous policy.

Môi trường đang chạy: tạo riêng bucket media-private qua MinIO Console với anonymous none, kiểm bucket media chỉ cho anonymous đọc khi cần PUBLIC và giữ dữ liệu lý thuyết hiện có. Không chạy lại init minio-mc chỉ để tạo bucket mới vì helper còn thiết lập policy media cho môi trường mới. Compose mới dùng anonymous download cho media và none cho media-private; gói apply không gọi Docker hoặc đổi policy máy đang chạy.

Legacy PRIVATE còn ở bucket public sẽ bị chặn khi xin URL. Không tự chuyển toàn bộ MP4/WebP hoặc xóa object cũ. Bản đồ video/thumbnail của ngân hàng phải được đối chiếu ở gói Content Builder rồi nạp/mapping có kiểm chứng vào private storage trước nghiệm thu Card 01. NULL target chỉ giữ tương thích với legacy đã có storage an toàn và được Practice attested; không mở ngoại lệ qua bucket public.

### Bucket PRIVATE không có policy

MinIO Java SDK 8.5.6 chuyển lỗi S3 `NoSuchBucketPolicy` thành chuỗi rỗng. Guard chấp nhận đúng kết quả này; null, whitespace/JSON lỗi, bucket thiếu, AccessDenied hoặc lỗi kết nối vẫn trả 503. Policy cho phép anonymous vẫn bị chặn. Test dùng SDK thật với HTTP S3 fixture để tránh chỉ mock nhánh exception mà SDK đã xử lý.

Video cũ tiếp tục dùng bucket theo `storageId` đã lưu (có thể là `media` nếu bucket này PRIVATE). `media-private` dành cho upload PRIVATE mới; bucket mới trống không làm API chuyển hướng đọc video cũ. Không chuyển object hoặc đổi storage metadata trong bản sửa này.
