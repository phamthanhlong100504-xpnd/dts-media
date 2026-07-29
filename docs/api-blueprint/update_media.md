# API Blueprint: Cập nhật thông tin Media (Update Media)

## Endpoint
`PATCH /api/v1/media/{media_id}`

## Prerequisites
- User đã xác thực (có Bearer Token hợp lệ).
- Bản ghi `medias` tồn tại và thuộc sở hữu của user đang đăng nhập (hoặc user có quyền Admin).

## Request Parameters
- `media_id` (path, required) – UUID của đối tượng media cần cập nhật.

## Request Body (JSON)
Chỉ truyền lên các trường cần thay đổi (Partial Update).
```json
{
  "visibility": "PUBLIC",
  "originalFilename": "new_avatar.png"
}
```

- `visibility` (optional, string) – Cập nhật quyền truy cập, giá trị `PUBLIC` hoặc `PRIVATE`.
- `originalFilename` (optional, string) – Cập nhật tên hiển thị của file (không làm thay đổi file vật lý trên Storage).

## Logic (server side)
1. Xác thực user thông qua token.
2. Tìm kiếm bản ghi `medias` trong cơ sở dữ liệu theo `media_id`.
3. Nếu không tìm thấy hoặc `deleted_at` không NULL, trả về lỗi **404 Not Found**.
4. Kiểm tra quyền hạn: Nếu `created_by` khác `user_id` hiện tại (và user không phải Admin), trả về lỗi **403 Forbidden**.
5. Cập nhật các trường được truyền lên từ request body vào bản ghi.
6. Lưu thay đổi vào cơ sở dữ liệu.
7. Trả về thông tin media sau khi cập nhật.

## Response (JSON, 200 OK)
```json
{
  "mediaId": "afc647b7-9045-471e-a2f0-f1a5ea983b05",
  "originalFilename": "new_avatar.png",
  "extension": "png",
  "mimeType": "image/png",
  "sizeBytes": 197133,
  "mediaType": "IMAGE",
  "visibility": "PUBLIC",
  "status": "READY",
  "url": "http://localhost:9000/media/uploads/2026/07/23/afc647b7-9045-471e-a2f0-f1a5ea983b05.png?X-Amz-...",
  "createdAt": "2026-07-23T22:57:43Z",
  "updatedAt": "2026-07-29T08:00:00Z"
}
```

## Errors
- **401 Unauthorized** – User chưa xác thực.
- **403 Forbidden** – User không có quyền chỉnh sửa (không phải người tạo ra file).
- **404 Not Found** – `media_id` không tồn tại hoặc đã bị xóa.
- **400 Bad Request** – Tham số trong body không hợp lệ (ví dụ visibility không đúng enum).
- **500 Internal Server Error** – Lỗi cập nhật cơ sở dữ liệu.
