
ALTER TABLE public.upload_policies
DROP CONSTRAINT IF EXISTS ck_upload_policies_target_type;

ALTER TABLE public.upload_policies
ADD CONSTRAINT ck_upload_policies_target_type
CHECK (
    target_type IN (
        'USER',
        'ROLE',
        'GROUP',
        'SERVICE',
        'TENANT',
        'QUESTION',
        'EXAM',
        'TRAFFIC_SIGN'
    )
);

-- 2. Create upload policy for traffic signs.
INSERT INTO public.upload_policies (
    id,
    target_type,
    enabled,
    max_file_size,
    allowed_mime_types,
    blocked_mime_types,
    visibility,
    metadata,
    created_by
)
VALUES (
    '0190ce1a-1000-7000-8000-000000000004'::uuid,
    'TRAFFIC_SIGN',
    TRUE,
    104857600,
    '["image/webp"]'::jsonb,
    '[]'::jsonb,
    'PUBLIC',
    '{}'::jsonb,
    '0190ce1a-0000-7000-8000-000000000000'::uuid
);
