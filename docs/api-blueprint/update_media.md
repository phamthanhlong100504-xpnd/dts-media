# API Blueprint: Update Media Metadata

## Endpoint

`PATCH /api/v1/media/{mediaId}`

## Request

Path mediaId là UUID. Query userId tùy chọn; nếu được truyền service so sánh với createdBy. Controller không lấy userId từ principal.

```json
{
  "originalFilename": "bien-bao-moi.webp",
  "visibility": "PUBLIC"
}
```

Trường null được giữ nguyên. Đây là đổi metadata, không upload lại binary/objectKey hoặc đổi nội dung file.

## Logic / Response — 200 OK

Yêu cầu media tồn tại và chưa soft-delete. Nếu userId có và khác chủ, ném SecurityException. Cập nhật các trường được gửi và updatedAt, trả MediaResponse trực tiếp với url=null; muốn URL thì gọi GET detail khi READY.

## Errors

Không có/đã xóa: IllegalArgumentException →400. SecurityException và lỗi DB không có mapping riêng trong advice hiện tại; không tự quy chúng về403. Giá trị visibility còn chịu CHECK của database.

## Source

[MediaController](../../src/main/java/com/dts/media/api/controller/MediaController.java), [MediaService](../../src/main/java/com/dts/media/application/service/MediaService.java), [UpdateMediaRequest](../../src/main/java/com/dts/media/api/request/UpdateMediaRequest.java). Xem [contract notes](contract_notes.md).
