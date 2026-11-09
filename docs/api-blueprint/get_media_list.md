# API Blueprint: List Media

## Endpoint

`GET /api/v1/media`

## Request Parameters

| Query | Kiểu | Mặc định / nghiệp vụ |
| --- | --- | --- |
| userId | UUID, tùy chọn | Người thường chỉ sub của mình; ROLE_ADMIN + media:manage có thể lọc owner khác hoặc toàn kho |
| mediaType | string, tùy chọn | Lọc mediaType |
| status | MediaStatus, tùy chọn | UPLOADING, READY, FAILED, DELETED |
| visibility | string, tùy chọn | PUBLIC/PRIVATE |
| page | int | 0, trang bắt đầu từ0 |
| size | int | 10; phạm vi 1–100 |

Yêu cầu JWT và media:read. Controller sắp xếp createdAt/id giảm dần; không nhận sort tùy ý. Khi không phải ADMIN manager, bỏ userId vẫn chỉ đọc createdBy=sub; hint khác sub bị403.

## Logic

Repository lọc trong scope quyền và chỉ trả deletedAt=null. PUBLIC + READY sinh URL GET; PRIVATE và trạng thái khác trả url=null trong list để tránh phát URL hàng loạt. Lỗi ký URL trả503 thay vì che lỗi bằng url=null. DTO/Page giữ nguyên cho thư viện Media admin.

## Response — 200 OK

Body là Spring Data Page<MediaResponse> trực tiếp, chứa content, number, size, totalElements, totalPages và metadata phân trang của Spring. Mỗi phần tử content theo [MediaResponse](get_media_url.md). Không bọc data/success hoặc đổi number thành page ở backend contract.

## Errors / Source

Page âm/size không dương gây IllegalArgumentException →400. Lỗi enum/UUID/query binding do Spring xử lý. Xem [MediaController](../../src/main/java/com/dts/media/api/controller/MediaController.java), [MediaRepository](../../src/main/java/com/dts/media/domain/repository/MediaRepository.java), [contract notes](contract_notes.md).
