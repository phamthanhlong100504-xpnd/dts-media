INSERT INTO storages (id, name, provider, endpoint, bucket, region, is_default, status, metadata, created_by)
VALUES (
    '0190ce1a-0000-7000-8000-000000000001'::uuid,
    'default-minio',
    'MINIO',
    'http://localhost:9000',
    'media',
    NULL,
    TRUE,
    'ACTIVE',
    '{}'::jsonb,
    '0190ce1a-0000-7000-8000-000000000000'::uuid
) ON CONFLICT (name) DO NOTHING;

INSERT INTO upload_policies (id, target_type, enabled, max_file_size, allowed_mime_types, blocked_mime_types, visibility, metadata, created_by)
VALUES (
    '0190ce1a-1000-7000-8000-000000000001'::uuid,
    'USER',
    TRUE,
    104857600, -- 100MB
    '["image/png", "image/jpeg", "image/gif", "video/mp4", "application/pdf"]'::jsonb,
    '[]'::jsonb,
    'PRIVATE',
    '{}'::jsonb,
    '0190ce1a-0000-7000-8000-000000000000'::uuid
);
