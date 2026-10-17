# API Blueprint: Soft-delete Media

## Endpoint

`DELETE /api/v1/media/{mediaId}`

## Request

Path mediaId là UUID. Query userId tùy chọn; không có body. Khi có userId, service so với createdBy. Method hiện không lấy chủ sở hữu từ JWT principal.

## Logic / Response

Tìm media chưa deletedAt. Ghi deletedAt và updatedBy rồi save; trả204 No Content. Không xóa binary khỏi bucket và không đổi status thành DELETED trong nhánh hiện tại. Các API list/detail loại hoặc từ chối hàng đã soft-delete. Không xóa media đang được Content Builder/Practice tham chiếu chỉ để dọn Git.

## Errors

Media không có/đã soft-delete: IllegalArgumentException →400. userId khác chủ: SecurityException, chưa có mapping403 riêng. Xóa lặp không phải một giao kèo idempotency trả204 của source này.

## Source

[MediaController](../../src/main/java/com/dts/media/api/controller/MediaController.java), [MediaService](../../src/main/java/com/dts/media/application/service/MediaService.java), [contract notes](contract_notes.md).
