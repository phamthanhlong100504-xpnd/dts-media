# Media API blueprints

Tài liệu theo source local đã thu thập ngày17/10/2026. Nhánh hiện tại `feature/traffic-sign-media` có base `f96e3244779966a046bc18e75e6cb39e9fe9d7ee`, bằng main lúc review. Java không thay đổi; V5 bổ sung policy TRAFFIC_SIGN cho endpoint hiện có.

| Method | Endpoint | Thành công | Tài liệu |
| --- | --- | --- | --- |
| POST | /api/v1/media/uploads/initialize | 201 | [initialize_upload.md](initialize_upload.md) |
| POST | /api/v1/media/uploads/{sessionId}/confirm | 202 | [confirm_upload.md](confirm_upload.md) |
| GET | /api/v1/media/{mediaId} | 200 | [get_media_url.md](get_media_url.md) |
| GET | /api/v1/media | 200 | [get_media_list.md](get_media_list.md) |
| PATCH | /api/v1/media/{mediaId} | 200 | [update_media.md](update_media.md) |
| DELETE | /api/v1/media/{mediaId} | 204 | [delete_media.md](delete_media.md) |

PUT file binary vào presignedUrl là request trực tiếp tới MinIO, không phải endpoint thứ7 của Media.

- [Luồng presigned upload](presigned_upload_guide.md)
- [Contract upload biển báo và policy V5](traffic_sign_upload.md)
- [Quy ước trạng thái/lỗi/xác thực đúng source](contract_notes.md)
- [Local setup](../LOCAL_SETUP.md), [nghiệp vụ](../MEDIA_FEATURES.md), [migration](../db/MIGRATION_CATALOG.md)

Response là DTO/Page trực tiếp, không có wrapper data/success như Practice hoặc Content Builder. Những trang giữ tên cũ được cập nhật phần casing, header và lỗi cho khớp controller/service hiện hành.
