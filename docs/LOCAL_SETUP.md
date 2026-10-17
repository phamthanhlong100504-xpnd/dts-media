# Chạy DTS Media trên máy mới

## 1. Repo và dependency

Java21, Spring Boot3.3.2, Gradle Wrapper8.8, PostgreSQL, Kafka và MinIO. Repo có wrapper JAR đã tracked; không cần chép JAR build từ máy khác. Port Media local8080; trong bố trí DTS hiện tại PostgreSQL Media publish5437 vào5432 và database mới tên media. Kafka dùng broker chung localhost9092. MinIO API9000, console9001.

Metadata nằm trong PostgreSQL, file binary nằm ở bucket MinIO. Git chứa code, migration, policy và tài liệu; không chứa dữ liệu bucket/MinIO volume, metadata người dùng hoặc token. Repo Content Builder chứa ảnh nguồn/importer cho dữ liệu câu hỏi/biển báo. .agents là submodule tài liệu, không cần chạy ứng dụng từ đó.

## 2. Hạ tầng

Nếu đang dùng môi trường DTS đã chạy ổn định, giữ các container/service hiện có. Công việc chuẩn bị Git không yêu cầu restart, chạy lại migration hoặc upload lại ảnh.

Máy mới cần PostgreSQL có database media, một broker Kafka truy cập được và MinIO có bucket media. File docker-compose.yml hiện có MinIO/Kafka/Zookeeper, không có PostgreSQL; cấu hình Kafka trong đó còn advertise địa chỉ máy chủ. Không lấy `docker compose up -d` toàn bộ file này làm lệnh chạy host-local mặc định nếu đang dùng broker chung DTS.

Với máy mới chỉ cần tạo MinIO theo compose có sẵn:

```powershell
Set-Location 'D:\DTS\dts-media'
docker compose up -d minio minio-mc
```

Hai service này tạo bucket media trong volume riêng theo cấu hình compose. Credentials điền cho ứng dụng phải khớp MinIO đang chạy; nếu dùng bộ khóa do nhóm quản lý thì provision bucket bằng cấu hình tương ứng, không dùng init helper mặc định khác khóa. Giữ volume cũ nếu đã có ảnh, không down -v để chuẩn bị source Git.

Kafka: dùng hạ tầng DTS đã cấu hình listener cho máy host, đặt SPRING_KAFKA_BOOTSTRAP_SERVERS theo broker đó. Khi Media chạy trong Docker, endpoint/listener phải truy cập được từ container; localhost của container không phải Windows host. Không tạo thêm broker ở cùng port9092 khi đã có dts-kafka.

PostgreSQL mới có thể được tạo riêng như ví dụ dưới đây sau khi nhập mật khẩu; lệnh là thao tác triển khai máy mới, không chạy trên DB/container đã tồn tại:

```powershell
# Sau khi thiết lập SPRING_DATASOURCE_PASSWORD ở mục3:
$env:POSTGRES_PASSWORD = $env:SPRING_DATASOURCE_PASSWORD
docker run -d --name dts-media-postgres `
  --env POSTGRES_DB=media --env POSTGRES_USER=postgres `
  --env POSTGRES_PASSWORD `
  --publish 5437:5432 `
  --mount type=volume,source=dts-media-postgres-data,target=/var/lib/postgresql/data `
  postgres:16-alpine
Remove-Item Env:POSTGRES_PASSWORD
```

Nếu container/DB đã được nhóm cấp thì dùng cấu hình đó, bỏ qua lệnh tạo container. Không import sample.sql như một migration mới; nguồn schema chuẩn ở src/main/resources/db/migration.

## 3. Biến môi trường cho Java chạy trên Windows

application.yml gốc có datasource mặc định trỏ môi trường máy chủ và Hibernate update. Đợt bàn giao này giữ nguyên YAML; các lệnh dưới chủ động override toàn bộ JDBC URL và tắt Hibernate tự sửa schema. Chỉ đặt DB_HOST=localhost sẽ vẫn giữ port5434 trong URL gốc, không đúng ví dụ PostgreSQL local5437.

.env.example là mẫu, không có giá trị credential. Spring Boot/Gradle không tự đọc .env khi chạy bootRun; export biến trong đúng terminal/IDE khởi động Media:

```powershell
Set-Location 'D:\DTS\dts-media'
$env:SERVER_PORT = '8080'
$env:SPRING_DATASOURCE_URL = 'jdbc:postgresql://localhost:5437/media'
$env:SPRING_DATASOURCE_USERNAME = 'postgres'
$env:SPRING_KAFKA_BOOTSTRAP_SERVERS = 'localhost:9092'
$env:EUREKA_CLIENT_ENABLED = 'false'
$env:MINIO_ENDPOINT = 'http://localhost:9000'
$env:MINIO_INTERNAL_ENDPOINT = 'http://localhost:9000'
$env:MEDIA_GET_URL_EXPIRY_MINUTES = '60'
$env:MEDIA_PUT_URL_EXPIRY_MINUTES = '15'
$env:SPRING_JPA_HIBERNATE_DDL_AUTO = 'none'
$env:SPRING_FLYWAY_ENABLED = 'false'
$env:SPRING_FLYWAY_VALIDATE_ON_MIGRATE = 'true'

function Read-DtsSecret([string]$Name) {
    $value = Read-Host $Name -AsSecureString
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($value)
    try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }
}
$env:SPRING_DATASOURCE_PASSWORD = Read-DtsSecret 'PostgreSQL password'
$env:MINIO_ACCESS_KEY = Read-DtsSecret 'MinIO access key'
$env:MINIO_SECRET_KEY = Read-DtsSecret 'MinIO secret key'
```

Giá trị khóa lấy từ hạ tầng của môi trường đích, không lấy từ tài liệu hay commit giá trị thật vào .env.example. Khi Media ở Docker và client ở máy khác: MINIO_ENDPOINT dùng hostname client truy cập được; MINIO_INTERNAL_ENDPOINT dùng địa chỉ worker truy cập được. URL đã ký không đổi host/query sau khi tạo. Cần CORS MinIO phù hợp origin frontend.

## 4. Migration và khởi động

Máy mới, database trống: xem [migration catalog](db/MIGRATION_CATALOG.md), cấu hình đúng DB rồi chủ động bật Flyway. Lệnh dưới **thực thi SQL migration còn thiếu và khởi động service**, không phải lệnh audit chỉ đọc:

```powershell
$env:SPRING_FLYWAY_ENABLED = 'true'
.\gradlew.bat bootRun
```

Flyway chạy V1 schema, V2 storage/user policy, V3 QUESTION, V4 EXAM, V5 TRAFFIC_SIGN. Hibernate đã được override none ở mục3. Với Linux/macOS dùng `sh ./gradlew bootRun` và export biến tương đương. Wrapper tải Gradle/dependency lần đầu nếu chưa có cache.

DB hiện hữu: backup và kiểm tra Flyway history/schema trước khi nâng cấp, chứng minh trên clone nếu cần. Không đánh dấu V5 đã chạy bằng cách sửa history, không chạy lại V1…V4 và không repair checksum để bỏ lỗi. V5 có INSERT ID cố định; chỉ thực thi đúng một lần qua migration version, không chạy tay lặp. Bản snapshot source không cho biết DB local đã chạy V5 hay chưa.

Sau khi schema cần thiết đã được xác nhận, chạy service thông thường với Flyway tắt để không thực thi migration trong lần khởi động đó:

```powershell
$env:SPRING_FLYWAY_ENABLED = 'false'
.\gradlew.bat bootRun
```

Khi pull migration mới, bật Flyway có chủ đích sau review/backup. Không dùng lệnh khởi động trong đợt chuẩn bị Git nếu process hiện đang chạy ổn định. application.yml/compose gốc vẫn giữ nguyên và không được helper sửa.

## 5. Nạp ảnh và liên kết dữ liệu các repo

V2 tạo storage default bucket media; MinioClient dùng endpoint/credentials của ứng dụng để ký URL. V5 chỉ tạo policy, không tạo221 media READY hay các file ảnh. Máy mới cần upload binary thật và giữ map UUID riêng theo môi trường.

1. Media/schema/MinIO/Kafka sẵn sàng, Identity/Gateway cung cấp phiên đăng nhập phù hợp.
2. Với ảnh câu hỏi, dùng tools/upload_question_images_2026.py trong Content Builder; policy QUESTION đến từ V3.
3. Với ảnh biển báo, dùng tools/upload_traffic_sign_images.py trong Content Builder; policy TRAFFIC_SIGN đến từ V5. Chờ media READY trước khi publish nội dung.
4. Theo DEPLOY_REFERENCE_CONTENT.md và docs/LOCAL_SETUP.md của Content Builder để giữ checkpoint/map ngoài Git, publish biển báo với mediaId mới, rồi đồng bộ sang Practice.

Không chép UUID/URL đã ký hoặc checkpoint của máy khác để giả định object đã tồn tại. Presigned URL hết hạn; dữ liệu nghiệp vụ giữ mediaId. Máy đã có ảnh hiển thị đúng không cần upload lại khi commit/push repo Media.

## 6. Gateway và frontend

Gateway host-local dùng MEDIA_SERVICE_URL=http://localhost:8080 trong chính environment khởi động Gateway. Frontend/API metadata đi qua route của dự án; PUT binary đi trực tiếp tới presignedUrl của MinIO. Không đưa Bearer Identity vào PUT MinIO.

Client sử dụng DTO trực tiếp của Media, phân biệt sessionId/mediaId và 202/READY. Các điều kiện hiện tại về ownership, parser token và lỗi được mô tả đúng source ở [contract notes](api-blueprint/contract_notes.md). Không gán thêm bảo đảm của Practice cho Media.

## 7. Vận hành source

Giữ wrapper, code/test, migration và tài liệu. .gradle/build/bin, log, .env và runtime object-storage không đưa vào commit. Không xóa bucket, database, build đang được dùng hoặc ảnh đang tham chiếu chỉ để dọn repo. Người phụ trách tự retest giao diện sau khi push trước khi chốt repo; công việc Git không tạo upload fixture hoặc restart service.
