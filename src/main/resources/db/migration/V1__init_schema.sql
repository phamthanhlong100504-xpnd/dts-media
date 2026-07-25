-- Table: storages
CREATE TABLE storages (
    id              UUID            NOT NULL,
    name            VARCHAR(255)    NOT NULL,
    provider        VARCHAR(30)     NOT NULL DEFAULT 'MINIO',
    endpoint        TEXT            NOT NULL,
    bucket          VARCHAR(255)    NOT NULL,
    region          VARCHAR(100)             DEFAULT NULL,
    is_default      BOOLEAN         NOT NULL DEFAULT FALSE,
    status          VARCHAR(30)     NOT NULL DEFAULT 'ACTIVE',
    metadata        JSONB           NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at      TIMESTAMPTZ              DEFAULT NULL,
    created_by      UUID            NOT NULL,
    updated_by      UUID                     DEFAULT NULL
);

ALTER TABLE storages
    ADD CONSTRAINT pk_storages PRIMARY KEY (id),
    ADD CONSTRAINT ck_storages_provider CHECK (provider IN ('MINIO', 'S3', 'AZURE_BLOB', 'GCS', 'LOCAL')),
    ADD CONSTRAINT ck_storages_status CHECK (status IN ('ACTIVE', 'INACTIVE'));

CREATE UNIQUE INDEX uq_storages_name ON storages (name);
CREATE INDEX ix_storages_created_by ON storages (created_by);


-- Table: medias
CREATE TABLE medias (
    id                  UUID            NOT NULL,
    storage_id          UUID            NOT NULL,
    object_key          TEXT            NOT NULL,
    original_filename   VARCHAR(500)    NOT NULL,
    extension           VARCHAR(20)     NOT NULL,
    mime_type           VARCHAR(255)    NOT NULL,
    size_bytes          BIGINT          NOT NULL DEFAULT 0,
    checksum            VARCHAR(128)             DEFAULT NULL,
    media_type          VARCHAR(30)     NOT NULL,
    visibility          VARCHAR(30)     NOT NULL DEFAULT 'PRIVATE',
    status              VARCHAR(30)     NOT NULL DEFAULT 'READY',
    metadata            JSONB           NOT NULL DEFAULT '{}'::jsonb,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at          TIMESTAMPTZ              DEFAULT NULL,
    created_by          UUID            NOT NULL,
    updated_by          UUID                     DEFAULT NULL
);

ALTER TABLE medias
    ADD CONSTRAINT pk_medias PRIMARY KEY (id),
    ADD CONSTRAINT fk_medias_storage_id FOREIGN KEY (storage_id) REFERENCES storages (id),
    ADD CONSTRAINT ck_medias_size_bytes CHECK (size_bytes >= 0),
    ADD CONSTRAINT ck_medias_media_type CHECK (media_type IN ('IMAGE', 'VIDEO', 'AUDIO', 'DOCUMENT', 'ARCHIVE', 'OTHER')),
    ADD CONSTRAINT ck_medias_visibility CHECK (visibility IN ('PUBLIC', 'PRIVATE')),
    ADD CONSTRAINT ck_medias_status CHECK (status IN ('UPLOADING', 'READY', 'FAILED', 'DELETED'));

CREATE UNIQUE INDEX uq_medias_object_key ON medias (object_key);
CREATE INDEX ix_medias_mime_type ON medias (mime_type);
CREATE INDEX ix_medias_checksum ON medias (checksum);
CREATE INDEX ix_medias_created_by ON medias (created_by);


-- Table: upload_policies
CREATE TABLE upload_policies (
    id                  UUID            NOT NULL,
    target_type         VARCHAR(30)     NOT NULL,
    enabled             BOOLEAN         NOT NULL DEFAULT TRUE,
    max_file_size       BIGINT                   DEFAULT NULL,
    allowed_mime_types  JSONB           NOT NULL DEFAULT '[]'::jsonb,
    blocked_mime_types  JSONB           NOT NULL DEFAULT '[]'::jsonb,
    visibility          VARCHAR(30)     NOT NULL,
    metadata            JSONB           NOT NULL DEFAULT '{}'::jsonb,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at          TIMESTAMPTZ              DEFAULT NULL,
    created_by          UUID            NOT NULL,
    updated_by          UUID                     DEFAULT NULL
);

ALTER TABLE upload_policies
    ADD CONSTRAINT pk_upload_policies PRIMARY KEY (id),
    ADD CONSTRAINT ck_upload_policies_target_type CHECK (target_type IN ('USER', 'ROLE', 'GROUP', 'SERVICE', 'TENANT')),
    ADD CONSTRAINT ck_upload_policies_max_file_size CHECK (max_file_size IS NULL OR max_file_size > 0),
    ADD CONSTRAINT ck_upload_policies_visibility CHECK (visibility IN ('PUBLIC', 'PRIVATE'));

CREATE INDEX ix_upload_policies_target_type ON upload_policies (target_type) WHERE deleted_at IS NULL;
CREATE INDEX ix_upload_policies_created_by ON upload_policies (created_by);


-- Table: upload_sessions
CREATE TABLE upload_sessions (
    id              UUID            NOT NULL,
    media_id        UUID                     DEFAULT NULL,
    uploader_id     UUID            NOT NULL,
    uploader_type   VARCHAR(30)     NOT NULL DEFAULT 'USER',
    device_id       UUID                     DEFAULT NULL,
    ip_address      VARCHAR(45)              DEFAULT NULL, -- Thay INET = VARCHAR(45) để JPA dễ map
    user_agent      TEXT                     DEFAULT NULL,
    status          VARCHAR(30)     NOT NULL DEFAULT 'PENDING',
    failure_reason  TEXT                     DEFAULT NULL,
    metadata        JSONB           NOT NULL DEFAULT '{}'::jsonb,
    started_at      TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at     TIMESTAMPTZ              DEFAULT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at      TIMESTAMPTZ              DEFAULT NULL,
    created_by      UUID            NOT NULL,
    updated_by      UUID                     DEFAULT NULL
);

ALTER TABLE upload_sessions
    ADD CONSTRAINT pk_upload_sessions PRIMARY KEY (id),
    ADD CONSTRAINT fk_upload_sessions_media_id FOREIGN KEY (media_id) REFERENCES medias (id),
    ADD CONSTRAINT ck_upload_sessions_uploader_type CHECK (uploader_type IN ('USER', 'SYSTEM', 'SERVICE')),
    ADD CONSTRAINT ck_upload_sessions_status CHECK (status IN ('PENDING', 'UPLOADING', 'COMPLETED', 'FAILED', 'CANCELLED', 'EXPIRED'));

CREATE INDEX ix_upload_sessions_uploader_id ON upload_sessions (uploader_id);
CREATE INDEX ix_upload_sessions_media_id ON upload_sessions (media_id);
CREATE INDEX ix_upload_sessions_created_by ON upload_sessions (created_by);
CREATE INDEX ix_upload_sessions_status_started ON upload_sessions (status, started_at) WHERE status IN ('PENDING', 'UPLOADING');
