INSERT INTO upload_policies (id, target_type, enabled, max_file_size, allowed_mime_types, blocked_mime_types, visibility, metadata, created_by)
VALUES (
    '0190ce1a-1000-7000-8000-000000000002'::uuid,
    'QUESTION',
    TRUE,
    104857600, -- 100MB
    '["image/png", "image/jpeg", "image/gif", "image/webp"]'::jsonb,
    '[]'::jsonb,
    'PUBLIC',
    '{}'::jsonb,
    '0190ce1a-0000-7000-8000-000000000000'::uuid
);