# DTS Media — chức năng và nghiệp vụ

## Ranh giới repo

Media quản lý metadata file, nơi lưu, chính sách và phiên upload. PostgreSQL giữ quan hệ và trạng thái; MinIO giữ binary; Kafka chuyển việc xác minh cho worker. Content Builder sở hữu nội dung câu hỏi/biển báo và tham chiếu UUID Media, Practice sở hữu dữ liệu học đã import. Pull Media không sao chép database/bucket của môi trường khác.

## Initialize và policy

UploadService tra UploadPolicy theo targetType, kiểm tra maxFileSize và allowedMimeTypes. Tạo Media UPLOADING, UploadSession PENDING trong transaction rồi ký URL PUT. UUID do backend sinh, objectKey theo ngày UTC và mediaId. sizeBytes là kích thước client khai báo, được worker so với object thực sau upload; không bị tự thay thành số khác khi xác minh.

Policy USER ở V2, QUESTION ở V3, EXAM ở V4; V5 thêm TRAFFIC_SIGN cho image/webp, tối đa104857600 byte, giá trị policy visibility PUBLIC. Java không tạo API biển báo mới hoặc convert ảnh; cùng initialize/confirm đang dùng cho các target khác. Các trường policy service chưa kiểm tra không được xem là bảo đảm enforcement, xem contract notes.

## Direct upload

Client PUT binary vào đúng presignedUrl, không đi qua Media API và không dùng multipart. URL mặc định sống15 phút. Bucket/credentials/CORS và endpoint phía client phải tương thích. MediaService không lưu binary trong thư mục source của Java.

## Confirm, Kafka và worker

Confirm chỉ nhận session PENDING, chuyển UPLOADING, phát event trong transaction. Listener AFTER_COMMIT gọi KafkaTemplate.send sang media-upload-verification, key=sessionId. Consumer gọi verifyUpload, dùng internalMinioClient.statObject để kiểm tra object và dung lượng.

Thành công ghi session COMPLETED và media READY; lỗi object/storage/size đi vào nhánh FAILED. Một số lỗi thiếu metadata có nhánh cập nhật khác, không giả định mọi lỗi đổi cả hai bảng. Service hiện không có durable outbox hoặc cam kết confirm lặp idempotent. HTTP202 xác nhận request, không đại diện cho kết quả xác minh.

## Đọc và quản lý metadata

- GET detail: cần tồn tại/chưa soft-delete/READY; sinh URL GET mặc định60 phút. Lỗi sinh URL được catch nên url có thể null.
- GET list: lọc userId/mediaType/status/visibility, page từ0, size mặc định10; loại hàng deletedAt khác null. Controller không chuyển query sort và không đặt max size100.
- PATCH: đổi originalFilename/visibility nếu trường khác null, không đổi binary. Response url=null.
- DELETE: ghi deletedAt/updatedBy, không xóa object MinIO và không đặt enum status DELETED trong code hiện tại.

MediaController không lấy principal cho nhóm CRUD; query userId chỉ được kiểm tra với chủ khi có truyền. UploadController parse sub từ Bearer, chưa xác minh chữ ký JWT tại parser; confirm service không so uploaderId với chủ session. Tài liệu mô tả hiện trạng để thành viên không suy ra hành vi chưa có, không sửa logic trong đợt bàn giao Git.

## Bảng và cấu trúc code

| Bảng / lớp | Vai trò |
| --- | --- |
| storages / Storage | Provider, endpoint metadata, bucket, default storage |
| medias / Media | UUID, objectKey, size/MIME, visibility, trạng thái, audit/soft-delete |
| upload_policies / UploadPolicy | TargetType và thông số upload |
| upload_sessions / UploadSession | Chủ upload, mediaId, trạng thái và lỗi xác minh |
| api/controller, api/form, api/request, api/response | Route và DTO |
| application/service | Transaction và nghiệp vụ metadata/upload |
| domain/entity, domain/repository | JPA và truy vấn |
| infrastructure/configuration, infrastructure/worker | MinIO, Kafka và consumer |
| src/main/resources/db/migration | SQL có version; nguồn schema chính thức |

## Phần bàn giao so với main

Chỉ V5 là file chức năng mới trong snapshot. Java/config/build không khác main đã đối chiếu. Bổ sung tài liệu hiện hành, ví dụ môi trường không secret và quy tắc Git; không chạy SQL, upload, restart hoặc thay đổi code đã được sử dụng trên web.

Đọc [local setup](LOCAL_SETUP.md), [API blueprints](api-blueprint/README.md) và [migration catalog](db/MIGRATION_CATALOG.md).
