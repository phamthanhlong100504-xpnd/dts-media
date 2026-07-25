-- Table: storages
-- Service: media
-- Entities mapped: storage
-- Engine: PostgreSQL
-- Mô tả: Bảng cấu hình các Storage Provider — mỗi row đại diện cho 1 đích lưu trữ vật lý (MINIO, S3, Azure Blob, GCS, hoặc Local).
-- Hệ thống hỗ trợ multi-provider: cho phép cấu hình nhiều storage đồng thời, chỉ 1 storage được đánh dấu `is_default = TRUE`
-- làm đích mặc định khi upload mới không chỉ định storage cụ thể.
--
-- ĐỊNH DANH: mỗi storage có `name` duy nhất (UNIQUE) làm business identifier để tham chiếu trong API và config.
-- `endpoint` lưu URL truy cập storage (vd `https://s3.amazonaws.com` hoặc `http://minio:9000`).
-- `bucket` là bucket/container mặc định, `region` tùy chọn cho cloud provider.
--
-- LIFECYCLE: storage có 2 trạng thái `ACTIVE`/`INACTIVE`. Khi chuyển sang `INACTIVE`, hệ thống ngừng upload mới
-- nhưng vẫn phục vụ đọc file đã lưu. Soft delete qua `deleted_at`.
--
-- AUDIT: `created_by`/`updated_by` là cross-service ref tới service `identity`, KHÔNG có FK.

CREATE TABLE storages (
    id              UUID            NOT NULL DEFAULT gen_random_uuid(),  -- Khóa chính UUIDv7; định danh duy nhất cho mỗi storage configuration.
    name            VARCHAR(255)    NOT NULL,                           -- Tên storage (business identifier); UNIQUE; dùng trong API và config để tham chiếu.
    provider        VARCHAR(30)     NOT NULL DEFAULT 'MINIO',           -- Nhà cung cấp storage; xác định SDK/protocol dùng để tương tác.
    endpoint        TEXT            NOT NULL,                           -- URL endpoint truy cập storage (vd `https://s3.amazonaws.com`, `http://minio:9000`).
    bucket          VARCHAR(255)    NOT NULL,                           -- Bucket/container mặc định trên storage provider.
    region          VARCHAR(100)             DEFAULT NULL,              -- (nullable) Region của cloud provider; NULL khi provider không yêu cầu (vd MINIO local, LOCAL).
    is_default      BOOLEAN         NOT NULL DEFAULT FALSE,             -- Đánh dấu storage mặc định; hệ thống dùng storage này khi upload không chỉ định đích cụ thể.
    status          VARCHAR(30)     NOT NULL DEFAULT 'ACTIVE',          -- Trạng thái hoạt động; `ACTIVE` = nhận upload mới + phục vụ đọc, `INACTIVE` = chỉ phục vụ đọc.
    metadata        JSONB           NOT NULL DEFAULT '{}'::jsonb,       -- Metadata mở rộng (JSON) — lưu các thuộc tính tùy biến (vd: access_key_ref, encryption config, custom tags).
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP, -- Thời điểm bản ghi được tạo.
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP, -- Thời điểm bản ghi được cập nhật gần nhất; auto-update qua trigger.
    deleted_at      TIMESTAMPTZ              DEFAULT NULL,              -- (nullable) Thời điểm soft delete; NULL = chưa xóa; có giá trị = đã xóa logic.
    created_by      UUID            NOT NULL,                           -- Actor tạo storage; cross-service ref tới `identity`, KHÔNG có FK.
    updated_by      UUID                     DEFAULT NULL               -- (nullable) Actor cập nhật gần nhất; cross-service ref tới `identity`, KHÔNG có FK; NULL khi chưa từng update.
);

ALTER TABLE storages
    ADD CONSTRAINT pk_storages PRIMARY KEY (id),
    ADD CONSTRAINT ck_storages_provider CHECK (provider IN ('MINIO', 'S3', 'AZURE_BLOB', 'GCS', 'LOCAL')),
    ADD CONSTRAINT ck_storages_status CHECK (status IN ('ACTIVE', 'INACTIVE'));

COMMENT ON COLUMN storages.id IS 'Khóa chính UUIDv7; định danh duy nhất cho mỗi storage configuration.';
COMMENT ON COLUMN storages.name IS 'Tên storage (business identifier); UNIQUE; dùng trong API và config để tham chiếu. Ví dụ: "primary-minio", "backup-s3".';
COMMENT ON COLUMN storages.provider IS 'Nhà cung cấp storage; xác định SDK/protocol dùng để tương tác. Giá trị hợp lệ: MINIO, S3, AZURE_BLOB, GCS, LOCAL.';
COMMENT ON COLUMN storages.endpoint IS 'URL endpoint truy cập storage (vd `https://s3.amazonaws.com`, `http://minio:9000`). Phải là URL hợp lệ bao gồm protocol.';
COMMENT ON COLUMN storages.bucket IS 'Bucket/container mặc định trên storage provider. Mỗi storage có đúng 1 bucket mặc định; có thể override ở tầng upload policy hoặc API.';
COMMENT ON COLUMN storages.region IS '(nullable) Region của cloud provider (vd `ap-southeast-1`, `us-east-1`); NULL khi provider không yêu cầu (MINIO local, LOCAL).';
COMMENT ON COLUMN storages.is_default IS 'Đánh dấu storage mặc định; hệ thống dùng storage này khi upload không chỉ định đích cụ thể. Tối đa 1 storage có is_default = TRUE tại mọi thời điểm.';
COMMENT ON COLUMN storages.status IS 'Trạng thái hoạt động. ACTIVE = nhận upload mới + phục vụ đọc. INACTIVE = ngừng nhận upload mới, chỉ phục vụ đọc file đã lưu.';
COMMENT ON COLUMN storages.metadata IS 'Metadata mở rộng (JSON) — lưu các thuộc tính tùy biến không thuộc schema chính (vd: access_key_ref, encryption config, custom tags); không tham gia logic nghiệp vụ chuẩn.';
COMMENT ON COLUMN storages.created_at IS 'Thời điểm bản ghi được tạo.';
COMMENT ON COLUMN storages.updated_at IS 'Thời điểm bản ghi được cập nhật gần nhất; auto-update qua trigger trg_storages_updated_at.';
COMMENT ON COLUMN storages.deleted_at IS '(nullable) Thời điểm soft delete; NULL = bản ghi đang hoạt động; có giá trị = đã xóa logic, không hiển thị trong danh sách mặc định.';
COMMENT ON COLUMN storages.created_by IS 'Actor tạo storage (admin/system); cross-service ref tới `identity`, KHÔNG có FK; phục vụ audit "ai tạo storage".';
COMMENT ON COLUMN storages.updated_by IS '(nullable) Actor cập nhật storage gần nhất; cross-service ref tới `identity`, KHÔNG có FK; NULL khi chưa từng update; phục vụ audit "ai sửa storage".';


-- Bất biến: tên storage phải duy nhất trong toàn hệ thống
CREATE UNIQUE INDEX uq_storages_name ON storages (name);
-- Tra cứu theo người tạo (vd: admin dashboard "ai tạo storage nào")
CREATE INDEX ix_storages_created_by ON storages (created_by);
