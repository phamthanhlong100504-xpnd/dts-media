# API Blueprint: Media Detail / Get URL

## Endpoint

`GET /api/v1/media/{mediaId}` — UUID media, không phải UUID upload session.

## Logic

Service yêu cầu media tồn tại, chưa soft-delete. PUBLIC + READY và không phải target mô phỏng giữ luồng public cho ảnh lý thuyết. Trường hợp khác cần media:read và đúng chủ, hoặc ROLE_ADMIN + media:manage. Chủ/admin đọc UPLOADING/FAILED với url=null để polling; READY sinh presigned GET URL qua storage active, hạn mặc định60 phút. Lỗi ký URL trả503, không trả thành công với URL null. HTTP200 metadata không chứng minh MinIO/Range đã nghiệm thu.

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
- Media chưa READY: chủ/admin nhận metadata với url=null. Thiếu cấu hình Storage vẫn là lỗi server; storage inactive/lỗi ký URL:503.
- PRIVATE thiếu JWT:401; sai chủ/thiếu quyền:403. Học viên lấy video thuộc snapshot qua [playback có scope](simulation_media_access.md), không qua UUID PRIVATE bất kỳ.
- Path UUID sai và lỗi binding do Spring xử lý.

Danh tính do security filter xác minh; không tin X-User-* hoặc query userId. Xem [contract_notes.md](contract_notes.md).

## Source

[MediaController](../../src/main/java/com/dts/media/api/controller/MediaController.java), [MediaService](../../src/main/java/com/dts/media/application/service/MediaService.java), [MediaResponse](../../src/main/java/com/dts/media/api/response/MediaResponse.java).
