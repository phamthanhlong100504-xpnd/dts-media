-- Table: medias
-- Service: media
-- Entities mapped: media
-- Engine: PostgreSQL
-- Mô tả: Bảng chính lưu metadata của tất cả media file trong hệ thống. Mỗi row đại diện cho 1 file đã được upload
-- hoặc đang trong quá trình upload lên một Storage cụ thể.
--
-- ĐỊNH DANH FILE: mỗi media được xác định qua `object_key` — là đường dẫn duy nhất (UNIQUE) trên storage provider
-- (vd `uploads/2024/01/abc123.jpg`). `original_filename` lưu tên file gốc do client gửi lên (phục vụ hiển thị,
-- KHÔNG dùng làm key trên storage). `extension` là phần mở rộng file (vd `jpg`, `pdf`).
--
-- PHÂN LOẠI: `media_type` phân loại file theo nhóm nghiệp vụ (IMAGE, VIDEO, AUDIO, DOCUMENT, ARCHIVE, OTHER);
-- `mime_type` lưu MIME type chi tiết (vd `image/jpeg`, `application/pdf`). Hai cột bổ trợ nhau:
-- `media_type` dùng cho filter nhanh theo nhóm, `mime_type` dùng khi cần xác định chính xác định dạng.
--
-- TOÀN VẸN: `checksum` (SHA256/MD5) phục vụ kiểm tra tính toàn vẹn file sau upload và phát hiện duplicate.
-- `size_bytes` lưu kích thước file tính bằng byte, CHECK >= 0.
--
-- TRUY CẬP: `visibility` kiểm soát quyền truy cập ở mức file: `PUBLIC` = ai cũng đọc được qua URL,
-- `PRIVATE` = yêu cầu presigned URL hoặc token.
--
-- LIFECYCLE: `status` theo dõi vòng đời file: `UPLOADING` (đang upload) → `READY` (sẵn sàng phục vụ) / `FAILED` (upload thất bại).
-- `DELETED` khi file bị xóa logic. Soft delete qua `deleted_at`.
--
-- QUAN HỆ: `storage_id` FK tới `storages(id)` — xác định file nằm trên storage nào.
--
-- AUDIT: `created_by`/`updated_by` là cross-service ref tới service `identity`, KHÔNG có FK.

CREATE TABLE medias (
    id                  UUID            NOT NULL DEFAULT gen_random_uuid(),  -- Khóa chính UUIDv7; định danh duy nhất cho mỗi media file.
    storage_id          UUID            NOT NULL,                           -- FK tới storages(id); xác định file nằm trên storage nào.
    object_key          TEXT            NOT NULL,                           -- Đường dẫn duy nhất trên storage provider (vd `uploads/2024/01/abc123.jpg`); UNIQUE; dùng để đọc/xóa file trên storage.
    original_filename   VARCHAR(500)    NOT NULL,                           -- Tên file gốc do client gửi lên (vd `báo-cáo-Q1.pdf`); phục vụ hiển thị, KHÔNG dùng làm key trên storage.
    extension           VARCHAR(20)     NOT NULL,                           -- Phần mở rộng file (vd `jpg`, `pdf`, `mp4`); trích xuất từ original_filename hoặc mime_type.
    mime_type           VARCHAR(255)    NOT NULL,                           -- MIME type chi tiết (vd `image/jpeg`, `application/pdf`); dùng khi cần xác định chính xác định dạng file.
    size_bytes          BIGINT          NOT NULL DEFAULT 0,                 -- Kích thước file tính bằng byte; CHECK >= 0; dùng để kiểm tra quota và hiển thị.
    checksum            VARCHAR(128)             DEFAULT NULL,              -- (nullable) Hash SHA256/MD5 của file; phục vụ kiểm tra toàn vẹn sau upload và phát hiện duplicate; NULL khi chưa tính xong.
    media_type          VARCHAR(30)     NOT NULL,                           -- Phân loại file theo nhóm nghiệp vụ; dùng cho filter nhanh theo nhóm.
    visibility          VARCHAR(30)     NOT NULL DEFAULT 'PRIVATE',         -- Quyền truy cập ở mức file; PRIVATE = cần presigned URL/token, PUBLIC = đọc tự do qua URL.
    status              VARCHAR(30)     NOT NULL DEFAULT 'READY',           -- Trạng thái vòng đời file; theo dõi quá trình từ upload tới sẵn sàng phục vụ hoặc xóa.
    metadata            JSONB           NOT NULL DEFAULT '{}'::jsonb,       -- Metadata mở rộng (JSON) — lưu các thuộc tính tùy biến (vd: dimensions, duration, thumbnail_url, exif data).
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP, -- Thời điểm bản ghi được tạo.
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP, -- Thời điểm bản ghi được cập nhật gần nhất; auto-update qua trigger.
    deleted_at          TIMESTAMPTZ              DEFAULT NULL,              -- (nullable) Thời điểm soft delete; NULL = chưa xóa; có giá trị = đã xóa logic.
    created_by          UUID            NOT NULL,                           -- Actor upload file (user/system/service); cross-service ref tới `identity`, KHÔNG có FK.
    updated_by          UUID                     DEFAULT NULL               -- (nullable) Actor cập nhật gần nhất; cross-service ref tới `identity`, KHÔNG có FK; NULL khi chưa từng update.
);

ALTER TABLE medias
    ADD CONSTRAINT pk_medias PRIMARY KEY (id),
    ADD CONSTRAINT fk_medias_storage_id FOREIGN KEY (storage_id) REFERENCES storages (id),
    ADD CONSTRAINT ck_medias_size_bytes CHECK (size_bytes >= 0),
    ADD CONSTRAINT ck_medias_media_type CHECK (media_type IN ('IMAGE', 'VIDEO', 'AUDIO', 'DOCUMENT', 'ARCHIVE', 'OTHER')),
    ADD CONSTRAINT ck_medias_visibility CHECK (visibility IN ('PUBLIC', 'PRIVATE')),
    ADD CONSTRAINT ck_medias_status CHECK (status IN ('UPLOADING', 'READY', 'FAILED', 'DELETED'));

COMMENT ON COLUMN medias.id IS 'Khóa chính UUIDv7; định danh duy nhất cho mỗi media file.';
COMMENT ON COLUMN medias.storage_id IS 'FK tới storages(id); xác định file nằm trên storage nào. Không thay đổi sau khi upload hoàn tất.';
COMMENT ON COLUMN medias.object_key IS 'Đường dẫn duy nhất trên storage provider (vd `uploads/2024/01/abc123.jpg`); UNIQUE; dùng để đọc/xóa file trên storage. Hệ thống tự sinh, KHÔNG dùng tên file gốc.';
COMMENT ON COLUMN medias.original_filename IS 'Tên file gốc do client gửi lên (vd `báo-cáo-Q1.pdf`); phục vụ hiển thị cho user, KHÔNG dùng làm key trên storage để tránh trùng lặp và ký tự đặc biệt.';
COMMENT ON COLUMN medias.extension IS 'Phần mở rộng file (vd `jpg`, `pdf`, `mp4`); trích xuất từ original_filename hoặc suy ra từ mime_type; dùng cho filter và icon hiển thị.';
COMMENT ON COLUMN medias.mime_type IS 'MIME type chi tiết (vd `image/jpeg`, `application/pdf`); xác định chính xác định dạng file; dùng cho Content-Type header khi phục vụ download và validation upload.';
COMMENT ON COLUMN medias.size_bytes IS 'Kích thước file tính bằng byte; CHECK >= 0; dùng để kiểm tra quota upload, hiển thị dung lượng, và thống kê sử dụng storage.';
COMMENT ON COLUMN medias.checksum IS '(nullable) Hash SHA256/MD5 của file; phục vụ kiểm tra toàn vẹn sau upload và phát hiện file duplicate; NULL khi file đang upload hoặc chưa tính hash xong.';
COMMENT ON COLUMN medias.media_type IS 'Phân loại file theo nhóm nghiệp vụ. IMAGE/VIDEO/AUDIO/DOCUMENT/ARCHIVE/OTHER. Dùng cho filter nhanh, không thay thế mime_type khi cần xác định chính xác.';
COMMENT ON COLUMN medias.visibility IS 'Quyền truy cập ở mức file. PUBLIC = đọc tự do qua URL công khai. PRIVATE = yêu cầu presigned URL hoặc token xác thực. Mặc định PRIVATE.';
COMMENT ON COLUMN medias.status IS 'Trạng thái vòng đời file. UPLOADING = đang upload. READY = sẵn sàng phục vụ. FAILED = upload thất bại. DELETED = đã xóa logic.';
COMMENT ON COLUMN medias.metadata IS 'Metadata mở rộng (JSON) — lưu các thuộc tính tùy biến không thuộc schema chính (vd: dimensions, duration, thumbnail_url, exif data, custom tags từ nghiệp vụ); không tham gia logic chuẩn.';
COMMENT ON COLUMN medias.created_at IS 'Thời điểm bản ghi được tạo (= thời điểm bắt đầu upload).';
COMMENT ON COLUMN medias.updated_at IS 'Thời điểm bản ghi được cập nhật gần nhất; auto-update qua trigger trg_medias_updated_at.';
COMMENT ON COLUMN medias.deleted_at IS '(nullable) Thời điểm soft delete; NULL = bản ghi đang hoạt động; có giá trị = đã xóa logic, file vật lý có thể được cleanup bởi scheduled job.';
COMMENT ON COLUMN medias.created_by IS 'Actor upload file (user/system/service); cross-service ref tới `identity`, KHÔNG có FK; phục vụ audit "ai upload file".';
COMMENT ON COLUMN medias.updated_by IS '(nullable) Actor cập nhật media gần nhất; cross-service ref tới `identity`, KHÔNG có FK; NULL khi chưa từng update; phục vụ audit "ai sửa metadata file".';


-- Bất biến: object_key phải duy nhất trên toàn hệ thống (1 file = 1 đường dẫn trên storage)
CREATE UNIQUE INDEX uq_medias_object_key ON medias (object_key);
-- Hot-path: filter media theo MIME type (vd: liệt kê tất cả ảnh, tất cả PDF)
CREATE INDEX ix_medias_mime_type ON medias (mime_type);
-- Tra cứu: tìm file theo checksum (phát hiện duplicate, kiểm tra toàn vẹn)
CREATE INDEX ix_medias_checksum ON medias (checksum);
-- Tra cứu: liệt kê media do 1 user upload (vd: "file của tôi")
CREATE INDEX ix_medias_created_by ON medias (created_by);
