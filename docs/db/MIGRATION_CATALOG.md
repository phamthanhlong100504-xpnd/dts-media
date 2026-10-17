# Migration catalog — DTS Media

Các file SQL là nguồn chuẩn để thành viên triển khai schema/policy. Hash dưới đây là SHA256 byte của snapshot local Windows, không phải checksum nội bộ Flyway. V1…V4 đang có index LF/working CRLF; giữ nguyên quy tắc checkout và nội dung. V5 mới được bảo toàn raw byte bằng .gitattributes riêng, không renormalize các migration đã tracked.

| Version | File | Nội dung | SHA256 local snapshot |
| --- | --- | --- | --- |
| V1 | [V1__init_schema.sql](../../src/main/resources/db/migration/V1__init_schema.sql) | Tạo storages, medias, upload_policies, upload_sessions và constraint/index | `381f06130b3778ae9cf12e00aee9c0d3fb94d2cbaa92c700e3f41f046fed8a2c` |
| V2 | [V2__seed_data.sql](../../src/main/resources/db/migration/V2__seed_data.sql) | Seed default storage bucket media và policy USER | `82bed9dee4767c233bc8bcfd7a6b4c3d11a004d2abb59aeb55eabc9f4fb1b1ec` |
| V3 | [V3__add_question_policy.sql](../../src/main/resources/db/migration/V3__add_question_policy.sql) | Bổ sung QUESTION và EXAM vào CHECK, seed policy QUESTION | `3210d34757ccebad3030c8c3bc6e2c10f76c53b449b54632541435dc51f9b059` |
| V4 | [V4__add_exam_policy.sql](../../src/main/resources/db/migration/V4__add_exam_policy.sql) | Seed policy EXAM | `d2618fe8bbd055b49a92fccefac821a2ad0f9a9ced666cea73dc1223a908facd` |
| V5 | [V5__add_traffic_sign_policy.sql](../../src/main/resources/db/migration/V5__add_traffic_sign_policy.sql) | Bổ sung TRAFFIC_SIGN vào CHECK và seed policy WebP PUBLIC | `d72833fbb82d86dd5d5275935e14935ee720f9922ff293eeb7914924422c5040` |

## Chạy có chủ đích

Flyway quản lý thứ tự và lịch sử. Khởi động với SPRING_FLYWAY_ENABLED=true thực thi file còn thiếu; đặt Hibernate DDL none để schema chỉ thay đổi bằng migration. Hướng dẫn triển khai ở [LOCAL_SETUP.md](../LOCAL_SETUP.md). Chuẩn bị Git không chạy lệnh này.

Snapshot chỉ thu source/Git, không đọc DB nên không chứng minh V5 đã được áp dụng ở local. Không sửa V1…V5 đã chạy, không đánh dấu success thủ công, không chạy lại INSERT policy có ID cố định. Nếu có thay đổi schema/policy tiếp theo, tạo version mới đúng quy trình.

docs/db/*.sql và sample.sql là tài liệu/sample có sẵn; không chạy chúng như một dòng migration thứ hai để tái tạo DB đã có. Không xóa metadata/bucket để làm migration trông như chưa chạy.

V5 chỉ thiết lập policy, không chứa binary ảnh, credential MinIO hoặc checkpoint import; các object và UUID Media của môi trường mới được tạo bằng luồng upload thực.
