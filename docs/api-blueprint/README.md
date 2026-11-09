# Media API blueprints

Tài liệu hiện hành trên `feat/simulation-media-access`, kế thừa `2bd9930` (policy V6). GAP Card 01 bổ sung JWT/ownership, scope video và V7; các contract upload/ảnh lý thuyết được giữ theo phần cập nhật bên dưới.

| Method | Endpoint | Thành công | Tài liệu |
| --- | --- | --- | --- |
| POST | /api/v1/media/uploads/initialize | 201 | [initialize_upload.md](initialize_upload.md) |
| POST | /api/v1/media/uploads/{sessionId}/confirm | 202 | [confirm_upload.md](confirm_upload.md) |
| GET | /api/v1/media/{mediaId} | 200 | [get_media_url.md](get_media_url.md) |
| GET | /api/v1/media | 200 | [get_media_list.md](get_media_list.md) |
| PATCH | /api/v1/media/{mediaId} | 200 | [update_media.md](update_media.md) |
| DELETE | /api/v1/media/{mediaId} | 204 | [delete_media.md](delete_media.md) |
| POST | /api/v1/media/simulation-playback | 200 | [simulation_media_access.md](simulation_media_access.md) |
| POST | /api/v1/media/simulation-catalog-thumbnail | 200 | [simulation_catalog_thumbnail.md](simulation_catalog_thumbnail.md) |

PUT file binary vào presignedUrl là request trực tiếp tới MinIO, không phải endpoint Media.

- [Luồng presigned upload](presigned_upload_guide.md)
- [Ma trận quyền và video mô phỏng — Part 0–4](simulation_media_access.md)
- [Contract upload biển báo và policy V5](traffic_sign_upload.md)
- [Quy ước trạng thái/lỗi/xác thực đúng source](contract_notes.md)
- [Local setup](../LOCAL_SETUP.md), [nghiệp vụ](../MEDIA_FEATURES.md), [migration](../db/MIGRATION_CATALOG.md)

Response là DTO/Page trực tiếp, không có wrapper data/success như Practice hoặc Content Builder. Những trang giữ tên cũ được cập nhật phần casing, header và lỗi cho khớp controller/service hiện hành.

- [Card 05 — Media thi thử mô phỏng](simulation_exam_media.md): eligibility và ticket playback/review riêng; [OpenAPI](simulation_exam_media.openapi.json).
