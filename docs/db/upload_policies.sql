-- Table: upload_policies
-- Service: media
-- Entities mapped: upload-policy
-- Engine: PostgreSQL
-- Mô tả: Bảng cấu hình chính sách upload — kiểm soát ai được upload, kích thước tối đa, loại file được phép/bị cấm.
-- Mỗi row là 1 policy áp dụng cho 1 đối tượng (`target_type`).
--
-- MỤC ĐÍCH: tách biệt logic kiểm soát upload khỏi business logic:
-- 1) Giới hạn kích thước file upload (`max_file_size`)
-- 2) Whitelist MIME types được phép (`allowed_mime_types`)
-- 3) Blacklist MIME types bị cấm (`blocked_mime_types`)
-- 4) Bật/tắt quyền upload cho từng đối tượng (`enabled`)
--
-- ĐỐI TƯỢNG: `target_type` xác định loại đối tượng mà policy áp dụng (vd: USER, ROLE, GROUP, SERVICE, TENANT).
-- Khi upload, hệ thống tìm policy phù hợp theo target_type, kiểm tra enabled, max_file_size,
-- allowed_mime_types, và blocked_mime_types trước khi cho phép upload.
--
-- ƯU TIÊN: nếu 1 đối tượng match nhiều policy, logic ưu tiên do application layer quyết định (không ở tầng DB).
--
-- MIME RULES: `allowed_mime_types` và `blocked_mime_types` là JSONB arrays.
-- Nếu `allowed_mime_types` không rỗng → chỉ cho phép các MIME trong danh sách.
-- Nếu `blocked_mime_types` không rỗng → cấm các MIME trong danh sách.
-- `blocked_mime_types` được kiểm tra SAU `allowed_mime_types` (blacklist override whitelist).
--
-- AUDIT: `created_by`/`updated_by` là cross-service ref tới service `identity`, KHÔNG có FK.

CREATE TABLE upload_policies (
    id                  UUID            NOT NULL DEFAULT gen_random_uuid(),  -- Khóa chính UUIDv7; định danh duy nhất cho mỗi upload policy.
    target_type         VARCHAR(30)     NOT NULL,                           -- Loại đối tượng mà policy áp dụng; xác định scope của policy.
    enabled             BOOLEAN         NOT NULL DEFAULT TRUE,              -- Bật/tắt policy; FALSE = chặn upload cho đối tượng thuộc target_type này.
    max_file_size       BIGINT                   DEFAULT NULL,              -- (nullable) Kích thước tối đa cho phép (byte); NULL = không giới hạn; CHECK > 0.
    allowed_mime_types  JSONB           NOT NULL DEFAULT '[]'::jsonb,       -- MIME types được phép upload (JSON array); rỗng [] = cho phép tất cả.
    blocked_mime_types  JSONB           NOT NULL DEFAULT '[]'::jsonb,       -- MIME types bị cấm upload (JSON array); rỗng [] = không cấm gì; blacklist override whitelist.
    metadata            JSONB           NOT NULL DEFAULT '{}'::jsonb,       -- Metadata mở rộng (JSON) — lưu các thuộc tính tùy biến (vd: max_total_storage, rate_limit, custom rules).
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP, -- Thời điểm bản ghi được tạo.
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP, -- Thời điểm bản ghi được cập nhật gần nhất; auto-update qua trigger.
    deleted_at          TIMESTAMPTZ              DEFAULT NULL,              -- (nullable) Thời điểm soft delete; NULL = chưa xóa.
    created_by          UUID            NOT NULL,                           -- Actor tạo policy (admin); cross-service ref tới `identity`, KHÔNG có FK.
    updated_by          UUID                     DEFAULT NULL               -- (nullable) Actor cập nhật gần nhất; cross-service ref tới `identity`, KHÔNG có FK.
);

ALTER TABLE upload_policies
    ADD CONSTRAINT pk_upload_policies PRIMARY KEY (id),
    ADD CONSTRAINT ck_upload_policies_target_type CHECK (target_type IN ('USER', 'ROLE', 'GROUP', 'SERVICE', 'TENANT')),
    ADD CONSTRAINT ck_upload_policies_max_file_size CHECK (max_file_size IS NULL OR max_file_size > 0);

COMMENT ON COLUMN upload_policies.id IS 'Khóa chính UUIDv7; định danh duy nhất cho mỗi upload policy.';
COMMENT ON COLUMN upload_policies.target_type IS 'Loại đối tượng mà policy áp dụng. USER = chính sách cho user cụ thể. ROLE = chính sách theo vai trò. GROUP = chính sách theo nhóm. SERVICE = chính sách cho service-to-service upload. TENANT = chính sách toàn tenant.';
COMMENT ON COLUMN upload_policies.enabled IS 'Bật/tắt policy. TRUE = policy đang hoạt động, áp dụng kiểm tra khi upload. FALSE = policy bị tắt, bỏ qua khi kiểm tra (tương đương chặn upload nếu không có policy nào khác match).';
COMMENT ON COLUMN upload_policies.max_file_size IS '(nullable) Kích thước tối đa cho phép upload (byte); NULL = không giới hạn kích thước; CHECK > 0 khi có giá trị. Ví dụ: 10485760 = 10MB.';
COMMENT ON COLUMN upload_policies.allowed_mime_types IS 'MIME types được phép upload (JSON array). Rỗng [] = cho phép tất cả các loại file. Có giá trị = chỉ cho phép các MIME trong danh sách (whitelist). Ví dụ: ["image/jpeg", "image/png", "application/pdf"].';
COMMENT ON COLUMN upload_policies.blocked_mime_types IS 'MIME types bị cấm upload (JSON array). Rỗng [] = không cấm gì. Có giá trị = cấm các MIME trong danh sách (blacklist). Blacklist được kiểm tra SAU whitelist (override). Ví dụ: ["application/x-executable", "text/html"].';
COMMENT ON COLUMN upload_policies.metadata IS 'Metadata mở rộng (JSON) — lưu các thuộc tính tùy biến không thuộc schema chính (vd: max_total_storage, rate_limit per minute, allowed_extensions, custom validation rules).';
COMMENT ON COLUMN upload_policies.created_at IS 'Thời điểm bản ghi được tạo.';
COMMENT ON COLUMN upload_policies.updated_at IS 'Thời điểm bản ghi được cập nhật gần nhất; auto-update qua trigger trg_upload_policies_updated_at.';
COMMENT ON COLUMN upload_policies.deleted_at IS '(nullable) Thời điểm soft delete; NULL = bản ghi đang hoạt động.';
COMMENT ON COLUMN upload_policies.created_by IS 'Actor tạo policy (admin); cross-service ref tới `identity`, KHÔNG có FK; phục vụ audit "ai tạo policy".';
COMMENT ON COLUMN upload_policies.updated_by IS '(nullable) Actor cập nhật policy gần nhất; cross-service ref tới `identity`, KHÔNG có FK; NULL khi chưa từng update.';


-- Tra cứu: tìm policy theo loại đối tượng (hot-path khi kiểm tra upload)
CREATE INDEX ix_upload_policies_target_type ON upload_policies (target_type) WHERE deleted_at IS NULL;
-- Tra cứu theo người tạo (audit)
CREATE INDEX ix_upload_policies_created_by ON upload_policies (created_by);
