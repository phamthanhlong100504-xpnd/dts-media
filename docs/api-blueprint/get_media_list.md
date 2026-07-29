# API Blueprint: Lấy danh sách Media (Get Media List)

## Endpoint
`GET /api/v1/media`

## Prerequisites
- User đã xác thực (có Bearer Token hợp lệ).
- Bảng `medias` có dữ liệu của user tương ứng.

## Request Parameters (Query)
- `page` (optional, integer) – Số thứ tự trang, bắt đầu từ 0. Mặc định: `0`.
- `size` (optional, integer) – Số lượng record trên mỗi trang. Mặc định: `10`.
- `mediaType` (optional, string) – Lọc theo loại file. Các giá trị hợp lệ: `IMAGE`, `VIDEO`, `AUDIO`, `DOCUMENT`.
- `status` (optional, string) – Lọc theo trạng thái xử lý. Các giá trị hợp lệ: `UPLOADING`, `READY`, `FAILED`.
- `visibility` (optional, string) – Lọc theo quyền truy cập. Các giá trị hợp lệ: `PRIVATE`, `PUBLIC`.

## Logic (server side)
1. Lấy thông tin `user_id` từ token xác thực.
2. Xây dựng câu truy vấn database để lấy danh sách `medias` thỏa mãn:
   - `created_by` = `user_id`.
   - `deleted_at` IS NULL (chỉ lấy các bản ghi chưa bị xóa).
   - Áp dụng các bộ lọc `mediaType`, `status`, `visibility` nếu client truyền lên.
3. Áp dụng phân trang (Pagination) với `offset = page * size` và `limit = size`. Order by `created_at` DESC (mới nhất lên đầu).
4. Sinh URL tải/xem trước (Presigned GET URL) cho các bản ghi có trạng thái `READY` nếu cấu hình file lưu trực tiếp (giống logic Get Media URL).
5. Trả về dữ liệu danh sách cùng metadata phân trang.

## Response (JSON, 200 OK)
```json
{
  "content": [
    {
      "mediaId": "afc647b7-9045-471e-a2f0-f1a5ea983b05",
      "originalFilename": "avatar.png",
      "extension": "png",
      "mimeType": "image/png",
      "sizeBytes": 197133,
      "mediaType": "IMAGE",
      "visibility": "PRIVATE",
      "status": "READY",
      "url": "http://localhost:9000/media/uploads/2026/07/23/afc647b7-9045-471e-a2f0-f1a5ea983b05.png?X-Amz-...",
      "createdAt": "2026-07-23T22:57:43Z"
    },
    {
      "mediaId": "b1b2b3b4-5678-471e-a2f0-f1a5ea983b06",
      "originalFilename": "video_intro.mp4",
      "extension": "mp4",
      "mimeType": "video/mp4",
      "sizeBytes": 15000000,
      "mediaType": "VIDEO",
      "visibility": "PUBLIC",
      "status": "UPLOADING",
      "url": null,
      "createdAt": "2026-07-24T10:00:00Z"
    }
  ],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 10,
    "totalElements": 2,
    "totalPages": 1
  }
}
```

## Errors
- **401 Unauthorized** – User chưa xác thực hoặc token hết hạn.
- **400 Bad Request** – Tham số truyền vào sai định dạng (ví dụ page, size không hợp lệ).
- **500 Internal Server Error** – Lỗi truy vấn cơ sở dữ liệu hoặc lỗi kết nối.
