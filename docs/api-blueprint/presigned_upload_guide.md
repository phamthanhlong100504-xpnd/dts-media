# Hướng Dẫn Tích Hợp Upload File Cho Client (Web / Mobile)

Tài liệu này hướng dẫn lập trình viên Client (Frontend Web, Mobile App, Third-party Integration) cách thực hiện tải file (Upload Media) lên hệ thống thông qua mô hình **Presigned URL (Tải trực tiếp không qua Server)**.

---

## 📋 Tổng Quan Luồng Xử Lý (3 Bước)

```
[ Client ] ------------ (1) POST /initialize ------------> [ Media Service ]
    |                                                            |
    |                                                    - Validate Policy & Quota
    |<----------- Presigned PUT URL + SessionID ------------------ - Sinh Presigned URL
    |
    |------------------ (2) PUT direct file ---------------------> [ MinIO / S3 Storage ]
    |
    |------------------ (3) POST /{sessionId}/confirm ----------> [ Media Service ]
                                                                 - Đưa vào hàng đợi xác thực ngầm
```

---

## 🛠️ Chi Tiết Các Bước Thực Hiện

### Bước 1: Khởi tạo phiên upload (Initialize Upload)

Client gửi thông tin metadata của file lên Media Service để xin cấp quyền và presigned URL.

- **Endpoint**: `POST /api/v1/media/uploads/initialize`
- **Headers**:
  - `Content-Type: application/json`
  - `X-User-Id: <UUID-nguoi-dung-dang-nhap>`
- **Body**:
  ```json
  {
    "fileName": "avatar_profile.png",
    "sizeBytes": 2097152,
    "mimeType": "image/png",
    "visibility": "PRIVATE",
    "targetType": "USER"
  }
  ```
- **Response thành công (`201 Created`)**:
  ```json
  {
    "sessionId": "0190ce1a-3000-7000-8000-000000000001",
    "mediaId": "0190ce1a-3000-7000-8000-000000000002",
    "presignedUrl": "http://localhost:9000/media/uploads/2026/07/23/0190ce1a-3000-7000-8000-000000000002.png?X-Amz-Algorithm=...",
    "objectKey": "uploads/2026/07/23/0190ce1a-3000-7000-8000-000000000002.png",
    "expiredAt": "2026-07-23T14:30:00Z"
  }
  ```

> ⚠️ **Lưu ý**: Giữ lại `sessionId` để dùng cho Bước 3 và `presignedUrl` để dùng cho Bước 2.

---

### Bước 2: Tải file trực tiếp lên Storage (Direct Upload)

Client dùng `presignedUrl` nhận được ở Bước 1 để đẩy file binary thô trực tiếp lên MinIO/S3. **Không gửi request này qua Server Backend.**

- **Endpoint**: `<Dán chính xác presignedUrl nhận từ Bước 1>`
- **HTTP Method**: `PUT`
- **Headers**:
  - `Content-Type: <mimeType_cua_file>` (phải khớp với `mimeType` khai báo ở Bước 1, ví dụ `image/png`)
- **Body**: Dữ liệu file binary thô (`File` object từ `<input type="file">` hoặc `Blob`).
- **Response mong muốn**: `200 OK`

#### Ví dụ Code JavaScript (Fetch API):
```javascript
// Giả sử 'file' là đối tượng File lấy từ HTML Input
// 'presignedUrl' và 'mimeType' lấy từ kết quả Bước 1

const uploadResponse = await fetch(presignedUrl, {
  method: 'PUT',
  headers: {
    'Content-Type': file.type
  },
  body: file // Truyền trực tiếp File object
});

if (uploadResponse.ok) {
  console.log("Upload lên MinIO thành công!");
}
```

---

### Bước 3: Xác nhận hoàn tất upload (Confirm Upload)

Sau khi upload lên MinIO thành công ở Bước 2, Client gọi API Confirm để báo cho Media Service biết và kích hoạt tiến trình xác thực ngầm.

- **Endpoint**: `POST /api/v1/media/uploads/{sessionId}/confirm`
- **Headers**:
  - `X-User-Id: <UUID-nguoi-dung-dang-nhap>`
- **Response thành công (`202 Accepted`)**:
  ```json
  {
    "sessionId": "0190ce1a-3000-7000-8000-000000000001",
    "mediaId": "0190ce1a-3000-7000-8000-000000000002"
  }
  ```

---

## ⚙️ Cơ Chế Xác Thực Ngầm (Background Verification Worker)

Sau khi Bước 3 được gọi, Media Service phát một sự kiện vào **Kafka Topic `media-upload-verification`**. Một **Worker Consumer (`UploadVerificationConsumer`)** sẽ lắng nghe ngầm và thực hiện các bước kiểm tra tự động:

1. **Gọi MinIO `statObject()`**: Kiểm tra xem file có thực sự tồn tại tại `objectKey` trên MinIO hay không.
2. **Kiểm tra dung lượng (`sizeBytes`)**: So sánh kích thước thực tế của file lưu trên MinIO với `sizeBytes` mà Client đã khai báo ở Bước 1.
3. **Cập nhật Database**:
   - **Xác thực hợp lệ**:
     - `upload_sessions.status` ➔ `COMPLETED`
     - `medias.status` ➔ `READY` (File bắt đầu phục vụ người dùng)
   - **Xác thực thất bại** (Không thấy file hoặc sai dung lượng):
     - `upload_sessions.status` ➔ `FAILED` (kèm `failure_reason` chi tiết)
     - `medias.status` ➔ `FAILED`

---

### Bước 4: Lấy URL xem/tải file (Get Media URL)

Sau khi upload thành công, Client sử dụng `mediaId` nhận từ Bước 3 để gọi API lấy URL xem/tải ảnh từ MinIO.

- **Endpoint**: `GET /api/v1/media/{mediaId}`
- **Response thành công (`200 OK`)**:
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
    "url": "http://localhost:9000/media/uploads/2026/07/23/afc647b7-9045-471e-a2f0-f1a5ea983b05.png?X-Amz-...",
    "createdAt": "2026-07-23T22:57:43Z"
  }
  ```

Client lấy trực tiếp đường dẫn `url` trong response này để gắn vào thẻ `<img src="...">` hoặc mở link xem/tải file.

---

## ⚡ Xử Lý Lỗi Thường Gặp (Troubleshooting)

1. **Lỗi `403 Forbidden` ở Bước 2**:
   - Do `presignedUrl` đã hết hạn (mặc định 15 phút). Hướng xử lý: Gọi lại Bước 1 để lấy presigned URL mới.
   - Do `Content-Type` header gửi ở Bước 2 không khớp với `mimeType` đã khai báo ở Bước 1.

2. **Lỗi `400 Bad Request` ở Bước 1 & Bước 4**:
   - File vượt quá kích thước cho phép của Policy (`max_file_size`).
   - Định dạng `mimeType` không nằm trong danh sách được phép (`allowed_mime_types`).
   - Ở Bước 4: Trạng thái file chưa `READY` (đang xác thực hoặc bị lỗi).

