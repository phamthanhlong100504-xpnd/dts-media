# 🚀 Media Service - Tài Liệu API & Hướng Dẫn Tích Hợp (API Reference & Quick Start)

Tài liệu này tổng hợp toàn bộ các API hiện có của **Media Service**, giúp lập trình viên Frontend (Web/Mobile), Tester hoặc đồng đội mới có thể nắm bắt và tích hợp nhanh chóng mà không cần đọc qua các bản thiết kế Blueprint chi tiết.

---

## 📌 1. Tổng Quan Kiến Trúc (Architecture Overview)

Hệ thống xử lý tải file theo mô hình **Presigned URL (Direct Upload)** để tối ưu băng thông và RAM của Backend:

```
[ Client / Frontend ] ----------- (1) POST /initialize -----------> [ Spring Boot API ]
         |                                                                |
         |                                                         - Validate Policy
         |<--------- Presigned PUT URL + sessionId + mediaId ------------- - Sinh Presigned PUT URL
         |
         |------------------- (2) PUT direct file ------------------------> [ MinIO Storage ]
         |
         |------------------- (3) POST /{sessionId}/confirm -------------> [ Spring Boot API ]
         |                                                                |
         |                                                         - DB: Status -> UPLOADING
         |                                                         - Bắn Event Kafka
         |                                                                |
         |                                                                v
         |                                                     [ Worker Consumer Ngầm ]
         |                                                                |
         |                                                         - Kiểm tra MinIO statObject
         |                                                         - DB: Session -> COMPLETED
         |                                                         - DB: Media   -> READY
         |
         |<------------------ (4) GET /api/v1/media/{mediaId} ------------|
         |                                                                |
         |<--------- Presigned GET URL (Xem/Tải file) -------------------- - Sinh Presigned GET URL
```

---

## 📋 2. Bảng Danh Sách API (API Endpoint Summary)

| STT | Phương thức | Đường dẫn API (Endpoint) | Chức năng | Trạng thái Response |
| :---: | :---: | :--- | :--- | :---: |
| **1** | `POST` | `/api/v1/media/uploads/initialize` | Khởi tạo phiên upload & lấy Presigned PUT URL | `201 Created` |
| **2** | `PUT` | `<presignedUrl>` | Đẩy file trực tiếp từ Client lên MinIO Storage | `200 OK` |
| **3** | `POST` | `/api/v1/media/uploads/{sessionId}/confirm` | Xác nhận upload hoàn tất & kích hoạt Worker xác thực | `202 Accepted` |
| **4** | `GET` | `/api/v1/media/{mediaId}` | Lấy thông tin media & Presigned GET URL để xem/tải file | `200 OK` |

---

## ⚡ 3. Hướng Dẫn Chạy Nhanh Dự Án (Quick Start)

### 🛠️ Bước 1: Khởi động Hạ tầng (Docker Services)
Mở Terminal tại thư mục dự án và chạy:
```bash
docker compose up -d
```
*   **PostgreSQL**: `localhost:5434` (DB: `media`, User: `postgres`, Pass: `K59Ptl*100504`)
*   **MinIO Storage**: `localhost:9000` (API), `localhost:9001` (Console UI - User: `minioadmin` / Pass: `minioadmin`)
*   **Kafka Cluster**: `localhost:9092`

### 🚀 Bước 2: Khởi động Server Spring Boot
```bash
./gradlew bootRun
```
Server sẽ lắng nghe tại cổng `http://localhost:8080`.

---

## 📖 4. Chi Tiết Các API

### 1️⃣ API Khởi tạo phiên Upload (Initialize Upload)

Dùng để khai báo thông tin file và lấy đường dẫn Presigned PUT URL từ MinIO.

- **Endpoint**: `POST /api/v1/media/uploads/initialize`
- **Headers**:
  - `Content-Type: application/json`
  - `X-User-Id: <UUID>` (ID người dùng đăng nhập)
- **Request Body**:
  ```json
  {
    "fileName": "avatar.png",
    "sizeBytes": 197133,
    "mimeType": "image/png",
    "visibility": "PRIVATE",
    "targetType": "USER"
  }
  ```
- **Response (`201 Created`)**:
  ```json
  {
    "sessionId": "64fc31e3-e1dc-46ad-8d92-d37ea4443e1c",
    "mediaId": "030fa728-2b95-4b6b-8ab9-66e09aa7bcd8",
    "presignedUrl": "http://localhost:9000/media/uploads/2026/07/23/030fa728-2b95-4b6b-8ab9-66e09aa7bcd8.png?X-Amz-Algorithm=...",
    "objectKey": "uploads/2026/07/23/030fa728-2b95-4b6b-8ab9-66e09aa7bcd8.png",
    "expiredAt": "2026-07-23T16:05:47.124Z"
  }
  ```

---

### 2️⃣ API Đẩy File trực tiếp lên Storage (Direct Upload)

Client gọi trực tiếp tới `presignedUrl` nhận từ API 1 để đẩy file binary thô. **Không qua Backend Spring Boot.**

- **Endpoint**: `<Dán presignedUrl nhận được từ API 1>`
- **HTTP Method**: `PUT`
- **Headers**:
  - `Content-Type: <mimeType_cua_file>` (phải trùng với `mimeType` ở API 1)
- **Request Body**: Binary Data thô của file (`File` object hoặc `Blob`)
- **Response**: `200 OK`

---

### 3️⃣ API Xác nhận Upload (Confirm Upload)

Gọi sau khi tải thành công ở API 2 để báo Backend biết và chạy Worker kiểm tra ngầm.

- **Endpoint**: `POST /api/v1/media/uploads/{sessionId}/confirm`
- **Headers**:
  - `X-User-Id: <UUID>`
- **Response (`202 Accepted`)**:
  ```json
  {
    "sessionId": "64fc31e3-e1dc-46ad-8d92-d37ea4443e1c",
    "mediaId": "030fa728-2b95-4b6b-8ab9-66e09aa7bcd8"
  }
  ```

---

### 4️⃣ API Lấy URL xem/tải file (Get Media Detail & View URL)

Sử dụng `mediaId` nhận từ API 3 để xin đường dẫn Presigned GET URL xem file.

- **Endpoint**: `GET /api/v1/media/{mediaId}`
- **Response (`200 OK`)**:
  ```json
  {
    "mediaId": "030fa728-2b95-4b6b-8ab9-66e09aa7bcd8",
    "originalFilename": "avatar.png",
    "extension": "png",
    "mimeType": "image/png",
    "sizeBytes": 197133,
    "mediaType": "IMAGE",
    "visibility": "PRIVATE",
    "status": "READY",
    "url": "http://localhost:9000/media/uploads/2026/07/23/030fa728-2b95-4b6b-8ab9-66e09aa7bcd8.png?X-Amz-Algorithm=...",
    "createdAt": "2026-07-23T22:50:46.774+07:00"
  }
  ```

> 💡 **Cách dùng**: Frontend chỉ cần lấy chuỗi trong thuộc tính `"url"` gắn vào thẻ `<img src="url">` hoặc trình phát video/âm thanh là hiển thị được ngay.

---

## 🔄 5. Vòng Đời Trạng Thái (Status Lifecycle)

### Bảng `upload_sessions`:
*   `PENDING`: Mới tạo phiên, đang chờ Client tải file lên MinIO.
*   `UPLOADING`: Client đã gọi Confirm, Worker đang xác thực ngầm.
*   `COMPLETED`: File đã được xác nhận tồn tại và khớp dung lượng trên MinIO.
*   `FAILED`: Tải thất bại (bỏ qua upload, sai dung lượng hoặc file bị lỗi).

### Bảng `medias`:
*   `UPLOADING`: Khởi tạo bản ghi, chưa phục vụ người dùng.
*   `READY`: Đã xác thực xong, file hợp lệ và sẵn sàng cho phép lấy URL xem/tải.
*   `FAILED`: File hỏng hoặc chưa hoàn tất tải.

---

## 🧪 6. Script cURL Test Trọn Gói (Copy & Run)

Bạn có thể copy toàn bộ đoạn script dưới đây dán vào Git Bash để tự động test toàn bộ quy trình 4 API:

```bash
# 1. Khai báo file cần test
FILE_PATH="C:/Users/Dai/Desktop/test_image.png"
FILE_NAME=$(basename "$FILE_PATH")
FILE_SIZE=$(stat -c%s "$FILE_PATH")

# 2. Gọi API Initialize (API 1)
RESPONSE=$(curl -s -X POST http://localhost:8080/api/v1/media/uploads/initialize \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 0190ce1a-0000-7000-8000-000000000002" \
  -d "{\"fileName\": \"$FILE_NAME\", \"sizeBytes\": $FILE_SIZE, \"mimeType\": \"image/png\", \"visibility\": \"PRIVATE\", \"targetType\": \"USER\"}")

PRESIGNED_URL=$(echo $RESPONSE | grep -o '"presignedUrl":"[^"]*' | grep -o '[^"]*$')
SESSION_ID=$(echo $RESPONSE | grep -o '"sessionId":"[^"]*' | grep -o '[^"]*$')

# 3. Đẩy file lên MinIO (API 2)
curl -X PUT "$PRESIGNED_URL" -H "Content-Type: image/png" --data-binary "@$FILE_PATH"

# 4. Xác nhận Upload (API 3)
CONFIRM_RES=$(curl -s -X POST "http://localhost:8080/api/v1/media/uploads/$SESSION_ID/confirm" \
  -H "X-User-Id: 0190ce1a-0000-7000-8000-000000000002")

MEDIA_ID=$(echo $CONFIRM_RES | grep -o '"mediaId":"[^"]*' | grep -o '[^"]*$')

# 5. Đợi 1 giây cho Worker xử lý ngầm rồi Lấy Link Xem File (API 4)
sleep 1
curl -s -X GET "http://localhost:8080/api/v1/media/$MEDIA_ID"
```
