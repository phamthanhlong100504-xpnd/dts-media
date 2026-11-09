# API Blueprint: Update Media Metadata

## Endpoint

`PATCH /api/v1/media/{mediaId}`

## Request

Path mediaId là UUID. JWT cần media:update và đúng chủ, hoặc ROLE_ADMIN + media:manage. Query userId tùy chọn chỉ được bằng sub; không dùng query để thay danh tính.

```json
{
  "originalFilename": "bien-bao-moi.webp",
  "visibility": "PUBLIC"
}
```

Trường null được giữ nguyên. Đây là đổi metadata, không upload lại binary/objectKey hoặc đổi nội dung file.

## Logic / Response — 200 OK

Yêu cầu media tồn tại/chưa soft-delete và kiểm ownership dù không gửi userId. Cập nhật trường được gửi, updatedAt và updatedBy=sub. Tên file được kiểm tra; target mô phỏng giữ PRIVATE và đúng extension. Trả MediaResponse trực tiếp với url=null; upload_target_type không được sửa qua body.

## Errors

Không có/đã xóa hoặc body sai:400; thiếu JWT:401; thiếu quyền/sai chủ:403. Giá trị visibility kiểm tại API và CHECK database.

## Source

[MediaController](../../src/main/java/com/dts/media/api/controller/MediaController.java), [MediaService](../../src/main/java/com/dts/media/application/service/MediaService.java), [UpdateMediaRequest](../../src/main/java/com/dts/media/api/request/UpdateMediaRequest.java). Xem [contract notes](contract_notes.md).
