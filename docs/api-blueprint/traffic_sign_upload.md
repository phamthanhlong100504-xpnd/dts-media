# API Blueprint: Upload ảnh biển báo — TRAFFIC_SIGN

## Phạm vi bổ sung so với main

V5 bổ sung targetType TRAFFIC_SIGN vào CHECK constraint và tạo policy upload. Java dùng endpoint upload chung hiện có; không có POST traffic-sign riêng trong Media. Đây là migration phải được commit, không phải cập nhật DB thủ công ngoài source.

## Policy / Prerequisites

| Thuộc tính | Giá trị trong V5 |
| --- | --- |
| id | 0190ce1a-1000-7000-8000-000000000004 |
| target_type | TRAFFIC_SIGN |
| enabled | true |
| max_file_size | 104857600 byte (100 MiB) |
| allowed_mime_types | image/webp |
| blocked_mime_types | mảng rỗng |
| visibility | PUBLIC |

Thực thi V5 bằng Flyway đúng môi trường trước khi dùng targetType này. Cần storage mặc định và bucket hoạt động, broker/worker đang chạy, URL MinIO truy cập được từ client. V5 không upload ảnh, không tạo mediaId, không sửa metadata của ảnh đã có. Không chạy SQL INSERT lặp vì V5 dùng ID cố định và không có ON CONFLICT.

## Request / Response

1. [Initialize](initialize_upload.md): POST /api/v1/media/uploads/initialize với Authorization Bearer và body dưới đây.

```json
{
  "fileName": "P.101.webp",
  "sizeBytes": 12345,
  "mimeType": "image/webp",
  "visibility": "PUBLIC",
  "targetType": "TRAFFIC_SIGN"
}
```

sizeBytes phải là kích thước file thật. API không convert JPEG/PNG thành WebP; phần chuẩn bị dữ liệu cần cung cấp file WebP. Service hiện lấy visibility từ request nên client phải gửi PUBLIC như policy/mục đích của ảnh biển báo.

2. Nhận201 với sessionId, mediaId, presignedUrl, objectKey, expiredAt. PUT nguyên binary vào đúng URL, không dùng multipart/form-data, không gửi token Identity sang MinIO.
3. PUT thành công mới POST /api/v1/media/uploads/{sessionId}/confirm cùng Bearer. Response202 chỉ có sessionId/mediaId.
4. Worker xác minh object tồn tại và dung lượng, ghi READY/COMPLETED hoặc FAILED. GET /api/v1/media/{mediaId} khi READY để lấy URL xem.
5. Content Builder lưu mediaId trong trường tham chiếu Media của biển báo; Practice nhận map qua import nội dung. Không lưu presignedUrl làm ID hay dùng UUID của máy khác.

## Logic / Errors

Chính sách MIME/size dùng UploadService hiện có. Trong nhóm chuẩn, ảnh PNG/JPEG gửi targetType TRAFFIC_SIGN bị từ chối do allow-list chỉ image/webp. Những ràng buộc policy chưa được service kiểm tra được nêu trong [contract notes](contract_notes.md); không bổ sung bảo đảm whitelist parser/ownership/quota ngoài code.

Thiếu policy, quá size, MIME sai, uploader UUID sai →400 qua IllegalArgumentException. Confirm lặp/không PENDING và GET trước READY hiện có thể500; không giả định409. Trạng thái READY phải được kiểm tra trước khi publisher ghi tham chiếu vào nội dung.

## Dữ liệu môi trường mới

Nguồn ảnh và upload tool nằm ở repo Content Builder: tools/upload_traffic_sign_images.py. Theo DEPLOY_REFERENCE_CONTENT.md của repo đó để upload221 ảnh, giữ checkpoint/map ngoài Git rồi publish biển báo. Việc commit migration/tài liệu Media không chạy importer hoặc tác động file đang hiển thị trên web.

## Source

[V5](../../src/main/resources/db/migration/V5__add_traffic_sign_policy.sql), [UploadService](../../src/main/java/com/dts/media/application/service/UploadService.java), [local setup](../LOCAL_SETUP.md).
