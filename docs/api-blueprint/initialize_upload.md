# API Blueprint: Initialize Upload (Phase 1)

## Endpoint
`POST /api/v1/media/uploads/initialize`

## Prerequisites
- Kafka configuration (`spring.kafka.bootstrap-servers`) defined in `application.properties`.
- Database tables exist: `storages`, `medias`, `upload_sessions`, `upload_policies`.
- At least one `storages` record with `is_default = true`.
- Relevant `upload_policies` for the requested `target_type`.

## Request Body (JSON)
```json
{
  "file_name": "string",          // Tên file gốc do client cung cấp
  "size_bytes": 12345,            // Kích thước dự kiến (byte)
  "mime_type": "image/jpeg",    // MIME type của file
  "visibility": "PRIVATE",      // PUBLIC hoặc PRIVATE
  "target_type": "USER"          // Loại đối tượng để áp dụng policy (USER, ROLE, ...)
}
```

## Logic (server side)
1. Validate request against `upload_policies` (max size, MIME whitelist/blacklist).
2. Retrieve default storage (`storages.is_default = true`).
3. Create a new record in `medias` with:
   - `status = 'UPLOADING'`
   - `object_key = 'uploads/yyyy/mm/dd/<uuid>.<ext>'`
   - `storage_id` = id of the default storage
   - `size_bytes` = value from request (placeholder, will be updated after verification)
   - other metadata as needed.
4. Create a new record in `upload_sessions` with:
   - `media_id` referencing the `medias` record
   - `status = 'PENDING'`
   - `uploader_id` = authenticated user/service id
   - `created_by` = same as `uploader_id`
5. Generate a presigned PUT URL using MinIO/S3 SDK for the `object_key`.

## Response (JSON, 201 Created)
```json
{
  "session_id": "<uuid>",
  "media_id": "<uuid>",
  "presigned_url": "https://minio.example.com/bucket/uploads/...",
  "object_key": "uploads/2024/10/abc123def456.jpg",
  "expired_at": "2026-07-20T12:00:00Z"
}
```

---

## Errors
- **400 Bad Request** – invalid payload or policy violation.
- **500 Internal Server Error** – failure generating presigned URL.

---

*Generated according to `api-blueprint-generator.md` and the implementation plan.*
