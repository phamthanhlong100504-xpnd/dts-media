-- DTS simulation media types. Add-only migration; existing object keys and media are unchanged.
-- Must be applied to LOCAL dts-media database via Flyway before simulation uploads.
ALTER TABLE public.upload_policies DROP CONSTRAINT IF EXISTS ck_upload_policies_target_type;
ALTER TABLE public.upload_policies ADD CONSTRAINT ck_upload_policies_target_type
CHECK (target_type IN (
    'USER', 'ROLE', 'GROUP', 'SERVICE', 'TENANT', 'QUESTION', 'EXAM', 'TRAFFIC_SIGN',
    'SIMULATION_VIDEO', 'SIMULATION_THUMBNAIL'
));

INSERT INTO public.upload_policies
(id, target_type, enabled, max_file_size, allowed_mime_types, blocked_mime_types, visibility, metadata, created_by)
SELECT
'0190ce1a-1000-7000-8000-000000000005'::uuid,
'SIMULATION_VIDEO', TRUE, 2147483648, '["video/mp4"]'::jsonb, '[]'::jsonb,
'PRIVATE', '{"purpose":"dts-simulation-video"}'::jsonb,
'0190ce1a-0000-7000-8000-000000000000'::uuid
WHERE NOT EXISTS (SELECT 1 FROM public.upload_policies WHERE target_type='SIMULATION_VIDEO' AND deleted_at IS NULL);

INSERT INTO public.upload_policies
(id, target_type, enabled, max_file_size, allowed_mime_types, blocked_mime_types, visibility, metadata, created_by)
SELECT
'0190ce1a-1000-7000-8000-000000000006'::uuid,
'SIMULATION_THUMBNAIL', TRUE, 20971520, '["image/webp"]'::jsonb, '[]'::jsonb,
'PRIVATE', '{"purpose":"dts-simulation-thumbnail"}'::jsonb,
'0190ce1a-0000-7000-8000-000000000000'::uuid
WHERE NOT EXISTS (SELECT 1 FROM public.upload_policies WHERE target_type='SIMULATION_THUMBNAIL' AND deleted_at IS NULL);
