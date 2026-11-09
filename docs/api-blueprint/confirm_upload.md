# API Blueprint: Confirm Upload

## Endpoint

`POST /api/v1/media/uploads/{sessionId}/confirm`

## Prerequisites / Request

PUT file vào đúng presignedUrl đã thành công. sessionId lấy từ initialize, session hiện PENDING. Header `Authorization: Bearer <access-token>`; controller không khai báo request body và không nhận checksum để xác minh.

## Logic

1. JWT đã xác minh; cần media:upload. Service khóa hàng session và kiểm đúng uploader từ sub, cả khi gọi lặp.
2. PENDING hợp lệ đổi UPLOADING trong transaction, kiểm media chưa xóa/đúng chủ và target chung mới cần quyền quản trị.
3. Phát UploadVerificationEvent. Listener AFTER_COMMIT gửi UploadVerificationMessage vào Kafka, key=sessionId.
4. Trả202; worker nhận sự kiện và gọi statObject bằng internalMinioClient, kiểm tra tồn tại/quyền truy cập và size so với sizeBytes khai báo.
5. Thành công: session COMPLETED, media READY. Sai kích thước/lỗi storage: session FAILED và khi có media thì media FAILED theo nhánh xử lý.

Gọi lặp UPLOADING/COMPLETED bởi đúng chủ trả cùng receipt, không phát event mới; FAILED/CANCELLED/EXPIRED trả409. Chờ trạng thái bằng GET detail, không gọi confirm để polling. Worker chỉ xử lý đúng cặp session/media ở UPLOADING, bổ sung Content-Type cho media mô phỏng.

## Response — 202 Accepted

```json
{
  "sessionId": "11111111-1111-4111-8111-111111111111",
  "mediaId": "22222222-2222-4222-8222-222222222222"
}
```

202 xác nhận request được nhận; không có trường status/grade và không chứng minh Kafka đã ack hay worker hoàn thành. Chờ media READY trước khi publish UUID vào nội dung.

## Errors

- Không tìm thấy session: IllegalArgumentException →400.
- Session terminal không được confirm:409; khác chủ/thiếu quyền:403.
- Bearer thiếu/không hợp lệ:401; lỗi binding header/path do Spring xử lý.

## Source

[UploadController](../../src/main/java/com/dts/media/api/controller/UploadController.java), [UploadService](../../src/main/java/com/dts/media/application/service/UploadService.java), [UploadVerificationConsumer](../../src/main/java/com/dts/media/infrastructure/worker/UploadVerificationConsumer.java).
