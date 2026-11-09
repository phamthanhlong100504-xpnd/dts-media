# API Blueprint: Soft-delete Media

## Endpoint

`DELETE /api/v1/media/{mediaId}`

## Request

Path mediaId là UUID, không có body. JWT cần media:delete và đúng chủ, hoặc ROLE_ADMIN + media:manage. Query userId tùy chọn chỉ là hint bằng sub; bỏ query không bỏ ownership.

## Logic / Response

Tìm media chưa deletedAt. Ghi deletedAt và updatedBy rồi save; trả204 No Content. Không xóa binary khỏi bucket và không đổi status thành DELETED trong nhánh hiện tại. Các API list/detail loại hoặc từ chối hàng đã soft-delete. Không xóa media đang được Content Builder/Practice tham chiếu chỉ để dọn Git.

## Errors

Media không có/đã soft-delete:400. Thiếu JWT:401; thiếu quyền/sai chủ/hint khác sub:403. Xóa lặp không cam kết204. Presigned URL đã cấp có thể còn dùng tới khi hết hạn.

## Source

[MediaController](../../src/main/java/com/dts/media/api/controller/MediaController.java), [MediaService](../../src/main/java/com/dts/media/application/service/MediaService.java), [contract notes](contract_notes.md).
