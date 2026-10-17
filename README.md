# DTS Media

Service Java21 quản lý metadata media và upload trực tiếp lên MinIO bằng presigned URL. Metadata/phiên upload/policy nằm trong PostgreSQL; Kafka chuyển yêu cầu kiểm tra file cho worker. Media local dùng port8080.

- [Cách chạy local và migration](docs/LOCAL_SETUP.md)
- [Chức năng và nghiệp vụ](docs/MEDIA_FEATURES.md)
- [API hiện hành và upload biển báo](docs/api-blueprint/README.md)
- [Danh mục migration V1–V5](docs/db/MIGRATION_CATALOG.md)
- [.env.example](.env.example): mẫu biến môi trường, không chứa secret và không tự được Spring Boot nạp.

Phần bổ sung chức năng so với main của đợt bàn giao này là migration V5 cho targetType TRAFFIC_SIGN. Endpoint Java được giữ nguyên; không có API upload biển báo mới tách riêng. Upload ảnh biển báo dùng quy trình initialize → PUT → confirm → lấy media READY hiện có.

Pull source không mang theo file trong bucket, metadata/UUID của máy khác hoặc database người dùng. Môi trường mới chạy migration rồi upload nội dung qua các công cụ Content Builder để có map Media UUID của chính môi trường đó. Không dùng bản export đã che credential làm source triển khai.
