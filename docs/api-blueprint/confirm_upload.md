# API Blueprint: Confirm Upload

## Endpoint

`POST /api/v1/media/uploads/{sessionId}/confirm`

## Prerequisites / Request

PUT file vào đúng presignedUrl đã thành công. sessionId lấy từ initialize, session hiện PENDING. Header `Authorization: Bearer <access-token>`; controller không khai báo request body và không nhận checksum để xác minh.

## Logic

1. Controller tách sub từ Bearer; service tìm session theo sessionId.
2. Chỉ session PENDING đi tiếp; đổi thành UPLOADING trong transaction.
3. Phát UploadVerificationEvent. Listener AFTER_COMMIT gửi UploadVerificationMessage vào Kafka, key=sessionId.
4. Trả202; worker nhận sự kiện và gọi statObject bằng internalMinioClient, kiểm tra tồn tại/quyền truy cập và size so với sizeBytes khai báo.
5. Thành công: session COMPLETED, media READY. Sai kích thước/lỗi storage: session FAILED và khi có media thì media FAILED theo nhánh xử lý.

Đây không phải endpoint finish có idempotency như Practice: confirm lại sau khi hết PENDING bị từ chối. Không gọi confirm lặp liên tục để chờ READY. Service hiện không so uploaderId với chủ session và không dùng row lock đặc tả; tài liệu không bổ sung các bảo đảm chưa có trong source.

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
- Session khác PENDING: IllegalStateException; GlobalExceptionHandler hiện không ánh xạ exception này, thường thành500 theo Spring mặc định. Không mặc định409 hoặc400.
- Bearer parser không đọc được sub:401; lỗi binding header/path do Spring xử lý.

## Source

[UploadController](../../src/main/java/com/dts/media/api/controller/UploadController.java), [UploadService](../../src/main/java/com/dts/media/application/service/UploadService.java), [UploadVerificationConsumer](../../src/main/java/com/dts/media/infrastructure/worker/UploadVerificationConsumer.java).
