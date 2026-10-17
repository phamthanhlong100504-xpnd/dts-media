# API Blueprint: Media Detail / Get URL

## Endpoint

`GET /api/v1/media/{mediaId}` — UUID media, không phải UUID upload session.

## Logic

Service yêu cầu media tồn tại, chưa soft-delete và status READY. Lấy storageId/bucket và sinh presigned GET URL bằng minio.endpoint; hạn mặc định60 phút qua MEDIA_GET_URL_EXPIRY_MINUTES. Endpoint trả metadata trực tiếp. URL có thể null nếu SDK sinh URL lỗi vì nhánh đó được catch; HTTP200 không chứng minh object đã được tải thành công từ MinIO.

## Response — 200 OK

```json
{
  "mediaId": "22222222-2222-4222-8222-222222222222",
  "originalFilename": "traffic-sign.webp",
  "extension": "webp",
  "mimeType": "image/webp",
  "sizeBytes": 12345,
  "mediaType": "IMAGE",
  "visibility": "PUBLIC",
  "status": "READY",
  "url": "http://localhost:9000/media/uploads/example.webp?<signed-query>",
  "createdAt": "2026-10-17T17:00:00Z"
}
```

Các giá trị là minh họa. Client dùng url khi khác null và lấy URL mới khi hết hạn; không lưu presigned URL làm định danh nội dung. Content Builder/Practice lưu mediaId.

## Errors

- Media không có hoặc deletedAt khác null: IllegalArgumentException →400 trong source hiện hành, không phải404.
- Media chưa READY hoặc thiếu cấu hình Storage: IllegalStateException; hiện không có mapping riêng, thường500.
- Path UUID sai và lỗi binding do Spring xử lý.

MediaController không có principal/header auth trong method này; xem [contract_notes.md](contract_notes.md) về ranh giới Gateway.

## Source

[MediaController](../../src/main/java/com/dts/media/api/controller/MediaController.java), [MediaService](../../src/main/java/com/dts/media/application/service/MediaService.java), [MediaResponse](../../src/main/java/com/dts/media/api/response/MediaResponse.java).
