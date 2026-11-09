# DTS Media — API reference

> Card 01 cập nhật xác thực/ownership/policy và confirm idempotency. Xem [contract hiện hành](docs/api-blueprint/simulation_media_access.md); các ví dụ lịch sử dưới đây không thay thế ma trận quyền mới.

Tài liệu hiện hành được tập trung tại [docs/api-blueprint/README.md](docs/api-blueprint/README.md).

Luồng upload chung và TRAFFIC_SIGN dùng trường camelCase, Authorization Bearer, response DTO trực tiếp và xác minh Kafka bất đồng bộ. Xem [upload biển báo](docs/api-blueprint/traffic_sign_upload.md) và [contract notes](docs/api-blueprint/contract_notes.md) trước khi nối client.

[Cách chạy local](docs/LOCAL_SETUP.md) mô tả biến môi trường và migration; tài liệu không chứa mật khẩu dùng thật. File này giữ đường dẫn tài liệu cũ để các liên kết đang dùng không bị mất.
