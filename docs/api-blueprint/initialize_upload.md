# API Blueprint: Initialize Upload

## Endpoint

`POST /api/v1/media/uploads/initialize`

## Prerequisites

- PostgreSQL có storages, medias, upload_sessions, upload_policies và policy của targetType.
- Có storage mặc định; bucket của storage tồn tại trong MinIO.
- MinIO endpoint bên ngoài trả trong URL phải truy cập được từ client.
- Header `Authorization: Bearer <access-token>` và `Content-Type: application/json`.

## Request Body

```json
{
  "fileName": "traffic-sign.webp",
  "sizeBytes": 12345,
  "mimeType": "image/webp",
  "visibility": "PUBLIC",
  "targetType": "TRAFFIC_SIGN"
}
```

Các trường dùng camelCase theo InitializeUploadForm. fileName/mimeType/visibility/targetType là chuỗi bắt buộc không trắng; sizeBytes bắt buộc và dương. visibility là PUBLIC/PRIVATE theo constraint DB; targetType dùng đúng chuỗi mà repository tra policy. Không gửi snake_case, mediaId hoặc sessionId để tự chọn ID.

## Logic

1. Security filter xác minh chữ ký/hạn/issuer/sub JWT; cần media:upload. USER thuộc cá nhân, mọi target khác cần ROLE_ADMIN + media:manage.
2. Service tìm policy chưa soft-delete, enabled; kiểm maxFileSize, MIME allow/block chính xác, visibility và định dạng mô phỏng.
3. Lấy storage có is_default=true và bucket tương ứng.
4. Sinh mediaId, objectKey uploads/yyyy/MM/dd/<mediaId>.<extension>; ghi media UPLOADING và upload_target_type. visibility phải khớp policy; mô phỏng luôn PRIVATE.
5. Sinh sessionId, ghi upload session PENDING.
6. Sinh presigned PUT URL bằng minio.endpoint, hạn mặc định15 phút; trả response sau transaction.

Các kiểm tra policy và ownership được thực thi tại service, không chỉ frontend. Xem [ma trận quyền](simulation_media_access.md) và [contract notes](contract_notes.md).

## Response — 201 Created

```json
{
  "sessionId": "11111111-1111-4111-8111-111111111111",
  "mediaId": "22222222-2222-4222-8222-222222222222",
  "presignedUrl": "http://localhost:9000/media/uploads/example.webp?<signed-query>",
  "objectKey": "uploads/2026/10/17/22222222-2222-4222-8222-222222222222.webp",
  "expiredAt": "2026-10-17T17:15:00Z"
}
```

Ví dụ minh họa shape, các ID/URL/thời điểm không phải dữ liệu thật hoặc một bộ URL/objectKey có thể dùng để upload. Dùng nguyên giá trị API trả về, không tự sửa hostname, path hoặc query sau khi ký. Không có wrapper data.

## Errors

- IllegalArgumentException như thiếu policy/storage, uploader UUID sai, vượt size hoặc MIME ngoài allow-list:400 theo GlobalExceptionHandler.
- Bearer thiếu/sai chữ ký/hạn/issuer/sub:401; thiếu quyền hoặc chọn target quản trị bằng tài khoản học viên:403.
- Lỗi sinh URL: RuntimeException,500 theo xử lý mặc định.
- Validation body do Spring xử lý; không giả định body lỗi giống ApiResponse của repo khác.

## Source

[UploadController](../../src/main/java/com/dts/media/api/controller/UploadController.java), [InitializeUploadForm](../../src/main/java/com/dts/media/api/form/InitializeUploadForm.java), [UploadService](../../src/main/java/com/dts/media/application/service/UploadService.java).
