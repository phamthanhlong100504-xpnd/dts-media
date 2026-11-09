# Media contract notes — source hiện hành

## Envelope và tên trường

Media trả DTO trực tiếp hoặc Spring Data Page, không bọc data/success. Giữ camelCase và sessionId/mediaId khác nhau. Endpoint/shape upload cũ không đổi. Contract quyền và video mới ở [simulation_media_access.md](simulation_media_access.md).

## Trạng thái và thời hạn

Initialize tạo media UPLOADING/session PENDING. Confirm đúng chủ khóa hàng, đổi UPLOADING và gửi event sau commit; lặp UPLOADING/COMPLETED trả cùng receipt, không phát event mới. Worker mới quyết định COMPLETED/READY hoặc FAILED, nên 202 không đồng nghĩa READY. Poll bằng GET detail. PUT mặc định15 phút, GET thường60 phút; playback mô phỏng có scope là300 giây.

Producer AFTER_COMMIT chưa dùng durable outbox hoặc chờ acknowledgement; worker/consumer không có bảo đảm recovery/replay mới. Không suy ra confirm idempotent đã giải quyết mọi lỗi broker/worker.

## Xác thực và chủ sở hữu

Filter xác minh chữ ký/hạn/issuer và sub UUID của access JWT; không chấp nhận refresh JWT. Permission tên thuần được chuyển PERM_ ở context. Không tin X-User-*. List mặc định scope sub; ghi metadata và confirm luôn kiểm ownership dù bỏ query userId. ADMIN quản trị toàn kho cần action permission và media:manage.

PUBLIC + READY không phải target mô phỏng giữ GET detail public cho ảnh lý thuyết. PRIVATE cần quyền và đúng chủ/admin; học viên lấy URL video qua ticket Practice ký cho lượt luyện/ngân hàng hợp lệ. Query userId của PATCH/DELETE chỉ là hint bằng sub; không đổi danh tính.

## Lỗi và URL

Missing/deleted media/session giữ IllegalArgumentException→400. SecurityException→403; filter thiếu/sai JWT→401; confirm terminal/playback chưa READY→409; storage inactive hoặc ký GET URL lỗi→503. Thiếu cấu hình storage và lỗi chưa ánh xạ vẫn là lỗi server. Validation/binding dùng Spring, không giả định wrapper service khác.

Chủ/admin đọc detail/list của media chưa READY với url=null; list PRIVATE luôn bỏ URL, lấy qua detail hoặc playback có scope. READY ký URL lỗi không còn trả HTTP200/url=null. PATCH trả url=null. DELETE soft-delete metadata, không xóa object hoặc đổi enum DELETED. URL đã cấp có thể dùng tới hết hạn; API Cache-Control no-store không tự đổi bucket policy/cache MinIO.

## Policy và storage

Initialize kiểm policy active/enabled, maxFileSize, MIME JSON allow/block chính xác và visibility khớp policy. Mô phỏng kiểm thêm MIME/extension/PRIVATE; mọi target ngoài USER cần quyền quản trị. V7 lưu target mới, giữ NULL cho legacy. Bucket lấy từ storage active; URL ký bằng MinioClient cấu hình, không sửa hostname/query sau ký.

minio.endpoint phải truy cập được từ client; internal-endpoint dùng cho worker. Credentials cần đúng bucket. Các enum provider trong schema không chứng minh đã triển khai mọi cloud provider. Kiểm size/Content-Type không thay kiểm codec/file magic hoặc nghiệm thu Range/CORS/video thật.
