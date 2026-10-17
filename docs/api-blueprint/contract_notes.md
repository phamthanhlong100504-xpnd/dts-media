# Media contract notes — source hiện hành

## Envelope và tên trường

Media trả DTO trực tiếp hoặc Spring Data Page; không bọc data/success. JSON dùng camelCase: fileName, sizeBytes, mimeType, targetType, sessionId, mediaId, presignedUrl, objectKey, expiredAt. sessionId và mediaId khác nhau; targetType TRAFFIC_SIGN bổ sung policy cho upload chung.

## Trạng thái và thời hạn

Initialize tạo media UPLOADING, session PENDING. Confirm đổi session UPLOADING và gửi event Kafka sau commit. Worker mới quyết định session COMPLETED/media READY hoặc FAILED. 202 không đồng nghĩa READY; confirm lặp không idempotent. Không chờ bằng cách confirm lại nhiều lần. Upload PUT mặc định15 phút và GET60 phút, cấu hình qua MEDIA_PUT_URL_EXPIRY_MINUTES/MEDIA_GET_URL_EXPIRY_MINUTES.

Producer AFTER_COMMIT không dùng durable outbox và không đợi acknowledgement trong service. Không áp dụng bảo đảm recovery/replay của Practice cho Media nếu source Media chưa thực hiện. Worker hiện bắt exception và log; tài liệu này không thay đổi cách retry/ack của repo.

## Ranh giới xác thực và chủ sở hữu

UploadController yêu cầu Authorization Bearer và giải mã phần payload để lấy sub; code trong repo Media không xác minh chữ ký/hạn JWT tại parser đó. Initialize kiểm tra sub có dạng UUID. Confirm nhận uploaderId nhưng service hiện không so với uploaderId của session. MediaController đọc metadata/list không nhận principal, PATCH/DELETE chỉ so chủ sở hữu nếu query userId được truyền.

Đó là mô tả source, không phải một bảo đảm ownership hay xác thực đầy đủ tại service. Quy tắc chặn truy cập bên ngoài của Gateway phải đối chiếu ở repo Gateway. Đợt bàn giao Git giữ nguyên Java/config, không đổi cơ chế này.

## Lỗi theo code

GlobalExceptionHandler hiện chỉ map IllegalArgumentException thành400 với body timestamp/status/error/message. Missing media/session và media đã xóa hiện đi qua nhánh này. IllegalStateException/SecurityException không có mapping riêng; không ghi chúng thành404/409/403 nếu chưa có code thực hiện. ResponseStatusException trong Bearer parser là401; validation/binding có body mặc định của Spring.

GET detail chỉ nhận media READY; URL có thể null nếu sinh URL lỗi được catch. List có thể chứa media chưa READY với url=null. PATCH trả url=null. DELETE soft-delete metadata, không xóa object trong MinIO và không đặt enum status DELETED.

## Policy và storage

Initialize tra UploadPolicy theo targetType, kiểm tra maxFileSize và allowedMimeTypes trên chuỗi lưu trong entity. Không tuyên bố có kiểm tra blockedMimeTypes, enabled, deletedAt hoặc ép visibility theo policy trong nhánh hiện tại. Bucket lấy từ storages, URL ký bằng MinioClient cấu hình; thay endpoint trong cột storages đơn lẻ không phải cam kết đổi host của URL đã ký.

minio.endpoint phải là URL mà client truy cập được; minio.internal-endpoint dành cho worker statObject. Không thay hostname/query của URL sau khi ký. Credentials của MinioClient phải truy cập được bucket; các enum provider khác trong schema không đồng nghĩa service hiện thực mọi cloud provider.
