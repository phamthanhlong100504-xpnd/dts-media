-- Table: upload_sessions
-- Service: media
-- Entities mapped: upload-session
-- Engine: PostgreSQL
-- Mô tả: Bảng theo dõi từng phiên upload file. Mỗi row đại diện cho 1 lần upload từ client tới hệ thống media,
-- bao gồm thông tin người upload, thiết bị, trạng thái tiến trình, và kết quả cuối cùng.
--
-- MỤC ĐÍCH: phục vụ 3 nhu cầu chính:
-- 1) Tracking tiến trình upload (client poll trạng thái qua session id)
-- 2) Audit trail (ai upload từ đâu, khi nào, bằng thiết bị gì)
-- 3) Retry/Resume (khi upload bị gián đoạn, có thể resume dựa trên session)
--
-- QUAN HỆ: `media_id` FK tới `medias(id)` — liên kết session với media file được tạo thành.
-- `media_id` có thể NULL khi upload chưa hoàn tất hoặc thất bại (file chưa được tạo trong bảng medias).
--
-- UPLOADER: `uploader_id` là actor thực hiện upload, `uploader_type` phân loại nguồn upload:
-- USER (người dùng), SYSTEM (job tự động), SERVICE (service khác gọi API).
-- `device_id`, `ip_address`, `user_agent` lưu thông tin thiết bị/môi trường phục vụ audit và bảo mật.
--
-- LIFECYCLE: `status` theo dõi vòng đời phiên upload:
-- PENDING (chờ bắt đầu) → UPLOADING (đang truyền dữ liệu) → COMPLETED (thành công) / FAILED (thất bại) / CANCELLED (hủy bởi user) / EXPIRED (hết thời gian).
-- `failure_reason` chỉ có giá trị khi `status = 'FAILED'`.
-- `started_at` và `finished_at` đánh dấu thời gian bắt đầu và kết thúc upload (phục vụ tính duration).
--
-- AUDIT: `created_by`/`updated_by` là cross-service ref tới service `identity`, KHÔNG có FK.

CREATE TABLE upload_sessions (
    id              UUID            NOT NULL DEFAULT gen_random_uuid(),  -- Khóa chính UUIDv7; định danh duy nhất cho mỗi phiên upload.
    media_id        UUID                     DEFAULT NULL,              -- (nullable) FK tới medias(id); media file được tạo thành từ phiên upload; NULL khi upload chưa hoàn tất hoặc thất bại.
    uploader_id     UUID            NOT NULL,                           -- Actor thực hiện upload; cross-service ref tới `identity`, KHÔNG có FK.
    uploader_type   VARCHAR(30)     NOT NULL DEFAULT 'USER',            -- Phân loại nguồn upload; xác định context xử lý (vd: USER có rate limit, SYSTEM không có).
    device_id       UUID                     DEFAULT NULL,              -- (nullable) Định danh thiết bị upload; phục vụ audit và chống abuse; NULL khi không xác định được.
    ip_address      INET                     DEFAULT NULL,              -- (nullable) Địa chỉ IP của client upload; phục vụ audit, geo-location, và chống abuse.
    user_agent      TEXT                     DEFAULT NULL,              -- (nullable) User-Agent header của client; phục vụ audit và thống kê theo trình duyệt/app.
    status          VARCHAR(30)     NOT NULL DEFAULT 'UPLOADING',       -- Trạng thái hiện tại của phiên upload; fast filter khi query.
    failure_reason  TEXT                     DEFAULT NULL,              -- (nullable) Mô tả nguyên nhân lỗi; chỉ có giá trị khi `status = 'FAILED'`; phục vụ debug và hiển thị cho user.
    metadata        JSONB           NOT NULL DEFAULT '{}'::jsonb,       -- Metadata mở rộng (JSON) — lưu thông tin bổ sung (vd: chunk info cho multipart upload, progress percentage, retry count).
    started_at      TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP, -- Thời điểm bắt đầu upload; dùng cùng `finished_at` để tính duration.
    finished_at     TIMESTAMPTZ              DEFAULT NULL,              -- (nullable) Thời điểm kết thúc upload (thành công hoặc thất bại); NULL khi đang upload; dùng cùng `started_at` để tính duration.
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP, -- Thời điểm bản ghi được tạo.
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP, -- Thời điểm bản ghi được cập nhật gần nhất; auto-update qua trigger.
    deleted_at      TIMESTAMPTZ              DEFAULT NULL,              -- (nullable) Thời điểm soft delete; NULL = chưa xóa.
    created_by      UUID            NOT NULL,                           -- Actor tạo phiên upload; cross-service ref tới `identity`, KHÔNG có FK.
    updated_by      UUID                     DEFAULT NULL               -- (nullable) Actor cập nhật gần nhất; cross-service ref tới `identity`, KHÔNG có FK.
);

ALTER TABLE upload_sessions
    ADD CONSTRAINT pk_upload_sessions PRIMARY KEY (id),
    ADD CONSTRAINT fk_upload_sessions_media_id FOREIGN KEY (media_id) REFERENCES medias (id),
    ADD CONSTRAINT ck_upload_sessions_uploader_type CHECK (uploader_type IN ('USER', 'SYSTEM', 'SERVICE')),
    ADD CONSTRAINT ck_upload_sessions_status CHECK (status IN ('PENDING', 'UPLOADING', 'COMPLETED', 'FAILED', 'CANCELLED', 'EXPIRED'));

COMMENT ON COLUMN upload_sessions.id IS 'Khóa chính UUIDv7; định danh duy nhất cho mỗi phiên upload.';
COMMENT ON COLUMN upload_sessions.media_id IS '(nullable) FK tới medias(id); media file được tạo thành từ phiên upload; NULL khi upload chưa hoàn tất hoặc thất bại (file chưa tồn tại trong bảng medias).';
COMMENT ON COLUMN upload_sessions.uploader_id IS 'Actor thực hiện upload (user_id/service_id); cross-service ref tới `identity`, KHÔNG có FK; phục vụ audit "ai upload file" và rate limiting.';
COMMENT ON COLUMN upload_sessions.uploader_type IS 'Phân loại nguồn upload. USER = người dùng cuối (có rate limit, UI feedback). SYSTEM = job tự động (batch import, migration). SERVICE = service khác gọi API nội bộ.';
COMMENT ON COLUMN upload_sessions.device_id IS '(nullable) Định danh thiết bị upload; phục vụ audit và chống abuse (vd: giới hạn upload đồng thời từ 1 thiết bị); NULL khi client không gửi device info.';
COMMENT ON COLUMN upload_sessions.ip_address IS '(nullable) Địa chỉ IP của client upload; kiểu INET hỗ trợ IPv4 và IPv6; phục vụ audit, geo-location, rate limiting, và phát hiện bất thường.';
COMMENT ON COLUMN upload_sessions.user_agent IS '(nullable) User-Agent header của client; phục vụ audit và thống kê theo trình duyệt/app/SDK version; NULL khi header không có.';
COMMENT ON COLUMN upload_sessions.status IS 'Trạng thái phiên upload. PENDING = chờ bắt đầu. UPLOADING = đang truyền dữ liệu. COMPLETED = hoàn tất thành công. FAILED = thất bại. CANCELLED = hủy bởi user. EXPIRED = hết thời gian cho phép.';
COMMENT ON COLUMN upload_sessions.failure_reason IS '(nullable) Mô tả nguyên nhân lỗi khi upload thất bại; chỉ có giá trị khi status = FAILED; phục vụ debug và hiển thị thông báo lỗi cho user.';
COMMENT ON COLUMN upload_sessions.metadata IS 'Metadata mở rộng (JSON) — lưu thông tin bổ sung không thuộc schema chính (vd: chunk info cho multipart upload, progress percentage, retry count, presigned URL expiry).';
COMMENT ON COLUMN upload_sessions.started_at IS 'Thời điểm bắt đầu upload; dùng cùng finished_at để tính duration; cũng dùng cho scheduler phát hiện session quá hạn (EXPIRED).';
COMMENT ON COLUMN upload_sessions.finished_at IS '(nullable) Thời điểm kết thúc upload (thành công hoặc thất bại); NULL khi đang trong quá trình upload; dùng cùng started_at để tính upload duration.';
COMMENT ON COLUMN upload_sessions.created_at IS 'Thời điểm bản ghi được tạo.';
COMMENT ON COLUMN upload_sessions.updated_at IS 'Thời điểm bản ghi được cập nhật gần nhất; auto-update qua trigger trg_upload_sessions_updated_at.';
COMMENT ON COLUMN upload_sessions.deleted_at IS '(nullable) Thời điểm soft delete; NULL = bản ghi đang hoạt động.';
COMMENT ON COLUMN upload_sessions.created_by IS 'Actor tạo phiên upload; cross-service ref tới `identity`, KHÔNG có FK; phục vụ audit.';
COMMENT ON COLUMN upload_sessions.updated_by IS '(nullable) Actor cập nhật gần nhất; cross-service ref tới `identity`, KHÔNG có FK; NULL khi chưa từng update.';


-- Tra cứu: tìm upload session theo người upload (vd: "lịch sử upload của user X")
CREATE INDEX ix_upload_sessions_uploader_id ON upload_sessions (uploader_id);
-- Tra cứu ngược: session nào đã tạo ra media file Y
CREATE INDEX ix_upload_sessions_media_id ON upload_sessions (media_id);
-- Tra cứu theo người tạo (audit)
CREATE INDEX ix_upload_sessions_created_by ON upload_sessions (created_by);
-- Scheduler: quét session đang UPLOADING/PENDING quá hạn để chuyển sang EXPIRED
CREATE INDEX ix_upload_sessions_status_started ON upload_sessions (status, started_at) WHERE status IN ('PENDING', 'UPLOADING');
