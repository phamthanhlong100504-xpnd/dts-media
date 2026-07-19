# Thiết kế API Upload Tài Nguyên Media (2 Giai Đoạn + Worker Kafka)

## Bối cảnh

Chúng ta cần thiết kế luồng API upload file trực tiếp lên Storage (MinIO/S3) thông qua cơ chế Presigned URL nhằm giảm tải cho server API. Luồng này chia làm 2 giai đoạn chính và kết hợp worker chạy ngầm sử dụng **Kafka** để xử lý hàng đợi:

1. **Giai đoạn 1 (Khởi tạo)**: 
   - Client gửi metadata của file.
   - Server kiểm tra Policy (kích thước tối đa, định dạng cho phép).
   - Server lấy Storage mặc định (từ bảng `storages`).
   - Server tạo đồng thời bản ghi trong cả 2 bảng:
     - Bảng `medias`: status = `'UPLOADING'` (thực thể chính lưu thông tin file).
     - Bảng `upload_sessions`: status = `'PENDING'` (lớp theo dõi phiên upload).
   - Server sinh Presigned PUT URL từ MinIO SDK và trả về cho Client.
2. **Client Upload**: Client dùng HTTP PUT đẩy trực tiếp file lên MinIO bằng Presigned URL.
3. **Giai đoạn 2 (Xác nhận)**: 
   - Client gọi API thông báo đã upload xong.
   - Server cập nhật `upload_sessions` sang trạng thái `'UPLOADING'` để khóa session (tránh ghi trùng lặp).
   - Server đẩy một event/message vào **Kafka Topic** `media-upload-verification`.
   - Trả về kết quả `202 Accepted` ngay cho Client.
4. **Worker xử lý ngầm (Kafka Consumer)**: 
   - Lắng nghe Kafka Topic `media-upload-verification`.
   - Khi nhận event, dùng S3 SDK kiểm tra thực tế file trên MinIO (`HeadObject` để lấy size thực tế).
   - Đối chiếu dung lượng thực tế so với dung lượng đăng ký.
   - Cập nhật trạng thái trong database:
     - Thành công: `medias.status` = `'READY'`, `upload_sessions.status` = `'COMPLETED'`.
     - Thất bại: `medias.status` = `'FAILED'`, `upload_sessions.status` = `'FAILED'` (kèm `failure_reason`).

---

## Quan hệ giữa bảng `storages` và `medias`

Để tránh hiểu nhầm về vai trò của bảng `storages`:
- **`storages` (Configuration Table)**: Chỉ lưu thông tin của các **Storage Provider / Buckets vật lý** (ví dụ: 1 dòng cho MinIO local, 1 dòng cho AWS S3, v.v.). Số lượng bản ghi trong bảng này rất ít và mang tính cấu hình hệ thống.
- **`medias` (File Metadata Table)**: Mỗi file được upload lên hệ thống sẽ tạo ra **1 bản ghi trong bảng `medias`**, liên kết tới storage tương ứng thông qua `storage_id`.
- Do đó, khi khởi tạo upload (`initialize`), API sẽ **không tạo bản ghi mới trong `storages`**, mà chỉ truy vấn lấy cấu hình storage mặc định/thích hợp sẵn có, sau đó tạo bản ghi mới trong `medias` và `upload_sessions`.

---

## Điều kiện tiên quyết trước khi triển khai Code (Prerequisites)

Vì đây là dự án mới tinh, trước khi bắt đầu sinh code logic cho các API này, chúng ta cần hoàn thành các bước thiết lập nền tảng:

1. **Database Schema & Migrations**:
   - Viết và chạy các migration file Flyway để tạo 4 bảng: `storages`, `medias`, `upload_sessions`, `upload_policies`.
2. **Cấu hình Kafka**:
   - Thêm dependency `spring-kafka` vào `build.gradle`.
   - Cấu hình bootstrap servers, serializer/deserializer trong `application.properties`.
   - Viết class cấu hình tạo topic `media-upload-verification`.
3. **Cấu hình MinIO / S3 SDK**:
   - Thêm dependency client SDK (vd `io.minio:minio` hoặc AWS S3 SDK).
   - Cấu hình credentials, endpoint, region trong `application.properties` và tạo Bean S3Client.
4. **Hạt giống dữ liệu (Data Seed)**:
   - Thêm script SQL seed khởi tạo ít nhất 1 storage cấu hình hoạt động trong `storages` và các policy mặc định trong `upload_policies` để luồng validate & sinh presigned url hoạt động được.

---

## Proposed Changes

Chúng ta sẽ tạo các tài liệu thiết kế API Blueprint trong thư mục `docs/api-blueprint/` tuân thủ quy tắc `api-blueprint-generator.md`. Các blueprint này sẽ bao gồm phần **Prerequisites** mô tả các yêu cầu nền tảng và **Missing Blueprint** khi file chưa tồn tại.

### 1. Tài liệu thiết kế API

#### [NEW] [initialize_upload.md](file:///c:/Users/Dai/Desktop/media/docs/api-blueprint/initialize_upload.md)
API Khởi tạo phiên upload (Phase 1).
- **Endpoint**: `POST /api/v1/media/uploads/initialize`
- **Request**: `file_name`, `size_bytes`, `mime_type`, `visibility` (PUBLIC/PRIVATE), `target_type` (phục vụ policy check).
- **Prerequisites**: Kafka config, DB tables, default storage record, upload policies.
- **Logic xử lý**:
  1. Lấy Policy tương ứng với uploader & `target_type` từ bảng `upload_policies`.
  2. Validate kích thước file tối đa (`max_file_size`), whitelist/blacklist MIME types.
  3. Chọn Storage mặc định (`is_default = true`) hoặc thích hợp từ bảng `storages`.
  4. Tạo bản ghi `medias` (status = `'UPLOADING'`) và sinh `object_key` an toàn (ví dụ: `uploads/yyyy/mm/dd/<uuid>.<ext>`).
  5. Tạo bản ghi `upload_sessions` (status = `'PENDING'`) trỏ tới `media_id` vừa tạo.
  6. Gọi MinIO S3 SDK sinh Presigned PUT URL tương ứng với `object_key`.
- **Response**: `session_id`, `media_id`, `presigned_url`, `object_key`, `expired_at`.

#### [NEW] [confirm_upload.md](file:///c:/Users/Dai/Desktop/media/docs/api-blueprint/confirm_upload.md)
API Xác nhận upload hoàn tất (Phase 2).
- **Endpoint**: `POST /api/v1/media/uploads/{session_id}/confirm`
- **Request**: `checksum` (optional, để verify toàn vẹn file nếu cần).
- **Prerequisites**: Kafka config, DB tables.
- **Logic xử lý**:
  1. Kiểm tra sự tồn tại và trạng thái của `upload_sessions` (phải là `PENDING`).
  2. Cập nhật trạng thái `upload_sessions` sang `'UPLOADING'` trong DB.
  3. Gửi message chứa `{ "session_id": "...", "media_id": "..." }` vào **Kafka topic** `media-upload-verification`.
  4. Trả về `202 Accepted` kèm `status: "UPLOADING"`.

---

## Kiến trúc Message Broker & Worker (Kafka)

### Kafka Topics
- Topic name: `media-upload-verification`
- Partition count: 3 (hoặc tùy cấu hình deploy)
- Message Key: `session_id` (đảm bảo các event của cùng một session được xử lý tuần tự trên cùng một partition).

### Luồng xử lý của Worker (Kafka Consumer)
1. **Receive Message**: Lấy ra `session_id` và `media_id`.
2. **Fetch Metadata**: Query DB để lấy thông tin `object_key`, `bucket`, `storage_endpoint` từ `medias` join với `storages`.
3. **Storage Verification**:
   - Gọi `HeadObject` từ S3 SDK tới Storage Provider tương ứng.
   - Nếu lỗi `404 Not Found` → File chưa được upload hoặc bị lỗi → Đánh dấu thất bại.
   - Lấy dung lượng file thực tế từ header `Content‑Length`.
4. **Validation**:
   - So sánh kích thước thực tế so với `size_bytes` đã đăng ký (cho phép sai số nhỏ hoặc khớp 100% tùy cấu hình).
5. **Database Update (Transaction)**:
   - Nếu hợp lệ:
     - `medias`: Set `status = 'READY'`, `size_bytes = actual_size`.
     - `upload_sessions`: Set `status = 'COMPLETED'`, `finished_at = NOW()`.
   - Nếu lỗi:
     - `medias`: Set `status = 'FAILED'`.
     - `upload_sessions`: Set `status = 'FAILED'`, `finished_at = NOW()`, `failure_reason = '...'`.

---

## Open Questions

> [!IMPORTANT]
> 1. **Cơ chế Worker**: Bạn muốn Worker kiểm tra qua SDK S3 (`HeadObject`) hay sử dụng event notification từ MinIO? (Khuyên dùng SDK trực tiếp).
> 2. **Checksum**: Có bắt buộc client tính và gửi checksum không, hay để optional?
> 3. **Prerequisite Check Behavior**: Khi thiếu một điều kiện, workflow có nên **abort** ngay hay **cảnh báo** và tiếp tục?

---

## Verification Plan

### Automated Tests
- Test các trường hợp:
  - Khởi tạo session thành công.
  - Khởi tạo session thất bại do vi phạm Policy.
  - Xác nhận upload thành công, trạng thái DB cập nhật đúng.
  - Worker xử lý đúng khi file tồn tại / không tồn tại.

### Manual Verification
- Deploy API, thực hiện flow: Initialize → lấy Presigned URL → Upload tới MinIO → Confirm → Kiểm tra DB trạng thái `READY`.
- Kiểm tra log Kafka để xác nhận message được gửi.
- Kiểm tra Worker logs để xác nhận xử lý.

---

## Workflow Update: Check Prerequisites Step

Trong workflow `11_feature_development.md` sẽ được chèn **Step 1.5 — Check Prerequisites** sau Step 1. Nội dung:
1. Đọc `application.properties` để xác nhận `spring.kafka.bootstrap-servers` tồn tại.
2. Kiểm tra bảng `flyway_schema_history` có migration cho `storages`, `medias`, `upload_sessions`, `upload_policies`.
3. Query `storages` để chắc chắn có ít nhất một bản ghi `is_default = true`.
4. Query `upload_policies` để có ít nhất một policy cho `target_type` được dùng.
- Nếu bất kỳ mục nào không thỏa, workflow dừng và trả về thông báo chi tiết.

---

## Documentation & Future Steps
- Khi các prerequisite đã được đáp ứng, chạy workflow để sinh blueprint và code.
- Sau khi blueprint được phê duyệt, tiếp tục bước **Generate Code** (workflow `04_generate_code.md`).
- Đảm bảo các tests được sinh và chạy thành công.

---

## Summary
- Bảng `storages` là cấu hình, không tạo mỗi file.
- Thêm bước kiểm tra prerequisites để tránh lỗi khi chưa chuẩn bị môi trường.
- Blueprint sẽ có mục Prerequisites và sẽ được tạo nếu chưa tồn tại.
