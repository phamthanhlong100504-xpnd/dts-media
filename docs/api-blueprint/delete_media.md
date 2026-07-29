# API Blueprint: Xóa Media (Delete Media)

## Endpoint
`DELETE /api/v1/media/{media_id}`

## Prerequisites
- User đã xác thực (có Bearer Token hợp lệ).
- Bản ghi `medias` tồn tại và thuộc sở hữu của user đang đăng nhập (hoặc user có quyền Admin).

## Request Parameters
- `media_id` (path, required) – UUID của đối tượng media cần xóa.

## Logic (server side)
1. Xác thực user thông qua token.
2. Tìm kiếm bản ghi `medias` trong cơ sở dữ liệu theo `media_id`.
3. Nếu không tìm thấy hoặc `deleted_at` không NULL (đã bị xóa trước đó), trả về lỗi **404 Not Found**.
4. Kiểm tra quyền hạn: Nếu `created_by` khác `user_id` hiện tại (và user không phải Admin), trả về lỗi **403 Forbidden**.
5. Đánh dấu xóa mềm (Soft delete):
   - Cập nhật trường `deleted_at` thành thời gian hiện tại (NOW).
   - Cập nhật `deleted_by` bằng `user_id` hiện tại.
6. Xử lý file vật lý (Asynchronous / Batch):
   - Bắn một message (event) vào Message Broker (VD: RabbitMQ/Kafka) chứa thông tin `media_id` hoặc đường dẫn file để worker xử lý việc xóa file trên MinIO S3 sau, nhằm không làm chậm quá trình phản hồi API.
   - *Lựa chọn thay thế*: Chạy một Cron Job định kỳ (hàng ngày) quét các bản ghi có `deleted_at` != NULL để gọi S3 SDK xóa file vật lý và sau đó xóa cứng (hard delete) hoặc giữ nguyên bản ghi trong Database tùy yêu cầu nghiệp vụ.
7. Trả về HTTP Status 204 No Content.

## Response (204 No Content)
(Không có body trả về).

## Errors
- **401 Unauthorized** – User chưa xác thực.
- **403 Forbidden** – User không có quyền xóa (không phải người tạo ra file).
- **404 Not Found** – `media_id` không tồn tại hoặc đã bị xóa.
- **500 Internal Server Error** – Lỗi cập nhật cơ sở dữ liệu hoặc lỗi kết nối.
