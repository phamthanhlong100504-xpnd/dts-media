-- Legacy media have no reliable upload target. Keep them NULL; do not infer
-- simulation ownership/publication from a filename, extension or MIME type.
ALTER TABLE public.medias ADD COLUMN upload_target_type VARCHAR(30);
ALTER TABLE public.medias ADD CONSTRAINT ck_medias_upload_target_type CHECK (
    upload_target_type IS NULL OR upload_target_type IN (
        'USER', 'ROLE', 'GROUP', 'SERVICE', 'TENANT', 'QUESTION', 'EXAM',
        'TRAFFIC_SIGN', 'SIMULATION_VIDEO', 'SIMULATION_THUMBNAIL'
    )
);

-- Separate private storage for new personal and simulation uploads. Preserve the current default/theory bucket.
-- Provision the actual MinIO bucket separately; SQL never changes object storage.
INSERT INTO public.storages (id, name, provider, endpoint, bucket, region, is_default, status, metadata, created_by)
SELECT '0190ce1a-0000-7000-8000-000000000002'::uuid,
       'private-minio', s.provider, s.endpoint, 'media-private', s.region,
       FALSE, 'ACTIVE', '{"purpose":"dts-private-media"}'::jsonb, s.created_by
FROM public.storages s
WHERE s.is_default = TRUE AND s.deleted_at IS NULL AND s.status = 'ACTIVE' AND s.provider = 'MINIO'
ON CONFLICT (name) DO NOTHING;
