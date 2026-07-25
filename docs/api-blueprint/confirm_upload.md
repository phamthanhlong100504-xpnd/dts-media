# API Blueprint: Xác nhận Upload (Phase 2)

## Endpoint
`POST /api/v1/media/uploads/{session_id}/confirm`

## Prerequisites
- Kafka configuration (`spring.kafka.bootstrap-servers`) defined in `application.properties`.
- Database tables exist: `storages`, `medias`, `upload_sessions`, `upload_policies`.
- Session `session_id` exists and has status `PENDING`.

## Request Parameters
- `session_id` (path) – UUID of the upload session.
- Body (JSON, optional):
```json
{
  "checksum": "string"   // SHA256 or MD5 of the uploaded file (optional)
}
```

## Logic (server side)
1. Verify the `upload_sessions` record exists and its status is `PENDING`.
2. Update `upload_sessions.status` to `UPLOADING` (lock the session).
3. Publish a message `{ "session_id": "...", "media_id": "..." }` to Kafka topic `media-upload-verification`.
4. Return **202 Accepted** indicating the verification is in progress.

## Response (JSON, 202 Accepted)
```json
{
  "session_id": "<uuid>",
  "media_id": "<uuid>"
}
```

## Errors
- **400 Bad Request** – invalid `session_id` or session not in a confirmable state.
- **500 Internal Server Error** – failure to publish Kafka message.

---

*Generated according to `api-blueprint-generator.md` and the approved implementation plan.*
