# DTS Media — hướng dẫn API

| Nội dung | Tài liệu |
| --- | --- |
| Danh sách endpoint và kiểu response | [API blueprints](api-blueprint/README.md) |
| Quy trình upload dành cho client | [Presigned upload guide](api-blueprint/presigned_upload_guide.md) |
| Phần bổ sung TRAFFIC_SIGN | [Traffic sign upload](api-blueprint/traffic_sign_upload.md) |
| Trạng thái, xác thực và lỗi đúng source | [Contract notes](api-blueprint/contract_notes.md) |
| Cấu hình/chạy máy mới | [Local setup](LOCAL_SETUP.md) |
| Bảng, worker và nghiệp vụ | [Media features](MEDIA_FEATURES.md) |
| Thứ tự migration | [Migration catalog](db/MIGRATION_CATALOG.md) |

Các tài liệu này mô tả source đang bàn giao; không phải báo cáo đã chạy lại upload hay kiểm tra runtime. Header của upload API là Authorization Bearer, không dùng X-User-Id để thay thế. Secret và checkpoint theo môi trường nằm ngoài Git.
