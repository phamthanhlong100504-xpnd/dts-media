# API Blueprint: Lấy URL và Thông Tin File (Get Media URL)

## Endpoint
`GET /api/v1/media/{media_id}`

## Prerequisites
- Database table `medias` và `storages` đã có dữ liệu.
- Bản ghi `medias` tương ứng với `media_id` có trạng thái `READY`.

## Request Parameters
- `media_id` (path, required) – UUID của đối tượng media.

## Logic (server side)
1. Tìm kiếm bản ghi `medias` trong cơ sở dữ liệu theo `media_id`.
2. Nếu không tìm thấy hoặc đã bị soft-deleted (`deleted_at` không NULL), trả về lỗi **404 Not Found**.
3. Nếu trạng thái file chưa sẵn sàng (`status != 'READY'`), trả về lỗi **400 Bad Request** ("File is not ready or failed verification").
4. Tìm cấu hình `Storage` tương ứng trong bảng `storages` theo `storage_id`.
5. Sử dụng MinIO S3 SDK sinh **Presigned GET URL** tương ứng với `object_key` (mặc định thời hạn hết hạn 60 phút).
6. Trả về thông tin media kèm đường dẫn `url` cho Client xem / tải về.

## Response (JSON, 200 OK)
```json
{
  "mediaId": "afc647b7-9045-471e-a2f0-f1a5ea983b05",
  "originalFilename": "avatar.png",
  "extension": "png",
  "mimeType": "image/png",
  "sizeBytes": 197133,
  "mediaType": "IMAGE",
  "visibility": "PRIVATE",
  "status": "READY",
  "url": "http://localhost:9000/media/uploads/2026/07/23/afc647b7-9045-471e-a2f0-f1a5ea983b05.png?X-Amz-Algorithm=...",
  "createdAt": "2026-07-23T22:57:43Z"
}
```

## Errors
- **404 Not Found** – `media_id` không tồn tại hoặc đã bị xóa.
- **400 Bad Request** – File chưa sẵn sàng (`status` là `UPLOADING` hoặc `FAILED`).
- **500 Internal Server Error** – Lỗi kết nối tới MinIO khi sinh URL.
