# API Blueprint: List Media

## Endpoint

`GET /api/v1/media`

## Request Parameters

| Query | Kiểu | Mặc định / nghiệp vụ |
| --- | --- | --- |
| userId | UUID, tùy chọn | Lọc createdBy nếu có; bỏ qua thì không giới hạn theo người |
| mediaType | string, tùy chọn | Lọc mediaType |
| status | MediaStatus, tùy chọn | UPLOADING, READY, FAILED, DELETED |
| visibility | string, tùy chọn | PUBLIC/PRIVATE |
| page | int | 0, trang bắt đầu từ0 |
| size | int | 10; controller hiện không đặt max100 |

Controller chỉ tạo PageRequest.of(page,size); không có query sort được chuyển tiếp và không cam kết thứ tự ổn định ngoài cấu hình hiện có. Query userId không phải một cơ chế xác minh chủ sở hữu từ JWT.

## Logic

Repository lọc các trường được truyền và chỉ trả deletedAt=null. READY có thể được gắn URL GET; trạng thái khác trả url=null. Lỗi lấy storage/sinh URL trong list được catch nên item READY cũng có thể có url=null.

## Response — 200 OK

Body là Spring Data Page<MediaResponse> trực tiếp, chứa content, number, size, totalElements, totalPages và metadata phân trang của Spring. Mỗi phần tử content theo [MediaResponse](get_media_url.md). Không bọc data/success hoặc đổi number thành page ở backend contract.

## Errors / Source

Page âm/size không dương gây IllegalArgumentException →400. Lỗi enum/UUID/query binding do Spring xử lý. Xem [MediaController](../../src/main/java/com/dts/media/api/controller/MediaController.java), [MediaRepository](../../src/main/java/com/dts/media/domain/repository/MediaRepository.java), [contract notes](contract_notes.md).
