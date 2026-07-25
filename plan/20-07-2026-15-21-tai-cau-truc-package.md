# Tái cấu trúc Package & Sửa Rules/Workflows

## Mô tả vấn đề

Có 2 lỗi nghiêm trọng trong code đã sinh:

### 1. Sai base package
- **Hiện tại**: `com.example.media` (package mặc định Spring Initializr)
- **Đúng**: `com.dts.media` (theo `build.gradle` → `group = 'com.dts'`)
- **Main class gốc** đã đúng: [MediaApplication.java](file:///c:/Users/Dai/Desktop/media/src/main/java/com/dts/media/MediaApplication.java) → `package com.dts.media`
- Cần **xoá toàn bộ** thư mục `com/example/` và tạo lại trong `com/dts/media/`

### 2. Sai cấu trúc thư mục (không theo rules)
Rules trong [structure.md](file:///c:/Users/Dai/Desktop/media/.agents/rules/templates/java/structure.md) đã quy định đúng cấu trúc, nhưng code sinh ra không tuân thủ.

**Cấu trúc sai (hiện tại)**:
```
com.example.media
├── api/                    ← package sai
│   ├── UploadController    ← thiếu thư mục controller/
│   └── dto/                ← dto không thuộc api
├── config/                 ← nên là infrastructure/configuration/
├── domain/                 ← thiếu entity/, repository/
│   ├── Media.java          ← nằm trực tiếp, thiếu entity/
│   ├── MediaStatus.java    ← enum, nên ở application/enums/
│   └── ...
├── message/                ← không có trong cấu trúc chuẩn
├── repository/             ← nên nằm trong domain/repository/
└── service/                ← nên nằm trong application/service/
```

**Cấu trúc đúng (cần đạt)**:
```
com.dts.media
├── api/
│   ├── controller/         → UploadController
│   ├── form/               → InitializeUploadRequest (request forms)
│   ├── response/           → InitializeUploadResponse, ConfirmUploadResponse
│   └── view/               → (trống cho giờ)
├── application/
│   ├── dto/                → UploadVerificationMessage
│   ├── enums/              → MediaStatus, UploadSessionStatus
│   ├── exception/          → (trống cho giờ)
│   ├── model/              → (trống cho giờ)
│   ├── service/            → UploadService
│   └── utils/              → (trống cho giờ)
├── config/                 → (trống cho giờ, app-level config)
├── domain/
│   ├── entity/             → Media, Storage, UploadSession, UploadPolicy
│   └── repository/         → MediaRepository, StorageRepository, UploadSessionRepository, UploadPolicyRepository
├── infrastructure/
│   ├── configuration/      → MinioConfig, KafkaConfig, ConsumerConfig
│   └── utils/              → (trống cho giờ)
└── MediaApplication.java
```

---

## Nguyên nhân gốc

### A. Rules đã đúng nhưng thiếu ràng buộc "base package"

File [structure.md](file:///c:/Users/Dai/Desktop/media/.agents/rules/templates/java/structure.md) và [package.md](file:///c:/Users/Dai/Desktop/media/.agents/rules/templates/java/package.md) dùng ví dụ `com.example.project` → agent hiểu nhầm đây là package thực tế thay vì placeholder.

> [!IMPORTANT]
> **Cần sửa**: Thêm rule bắt buộc agent phải **đọc `build.gradle`** hoặc `settings.gradle` để xác định `group` → base package trước khi sinh code.

### B. Workflow `04_generate_code.md` thiếu bước "xác định base package"

Workflow liệt kê các bước nhưng không có bước bắt buộc xác định `base package` từ dự án hiện tại.

---

## Thay đổi đề xuất

### Phần 1: Sửa Rules

#### [MODIFY] [structure.md](file:///c:/Users/Dai/Desktop/media/.agents/rules/templates/java/structure.md)
- Thay `com.example.project` bằng `{group}.{artifact}` (placeholder rõ ràng)
- Thêm rule `STRUCTURE-026`: **PHẢI đọc `build.gradle` hoặc `pom.xml` để xác định base package trước khi sinh code**
- Thêm rule `STRUCTURE-027`: **KHÔNG BAO GIỜ dùng `com.example` làm base package**

#### [MODIFY] [package.md](file:///c:/Users/Dai/Desktop/media/.agents/rules/templates/java/package.md)
- Đồng bộ thay đổi giống `structure.md` (2 file hiện đang trùng nội dung)

---

### Phần 2: Sửa Workflow

#### [MODIFY] [04_generate_code.md](file:///c:/Users/Dai/Desktop/media/.agents/workflows/04_generate_code.md)
- Thêm **Step 0.5 — Xác định Base Package**: Bắt buộc đọc `build.gradle` → `group` + `settings.gradle` → `rootProject.name` để xác định `{base_package} = {group}.{rootProject.name}`
- Thêm validation checklist: "Base package khớp với `build.gradle`"

#### [MODIFY] [11_feature_development.md](file:///c:/Users/Dai/Desktop/media/.agents/workflows/11_feature_development.md)
- Thêm prerequisite kiểm tra base package

---

### Phần 3: Tái cấu trúc Code

#### Xoá toàn bộ `com/example/` (18 files)
#### Tạo lại tất cả files dưới `com/dts/media/` với package & cấu trúc đúng

| File hiện tại (sai) | Vị trí mới (đúng) |
|---|---|
| `com.example.media.api.UploadController` | `com.dts.media.api.controller.UploadController` |
| `com.example.media.api.dto.InitializeUploadRequest` | `com.dts.media.api.form.InitializeUploadForm` |
| `com.example.media.api.dto.InitializeUploadResponse` | `com.dts.media.api.response.InitializeUploadResponse` |
| `com.example.media.api.dto.ConfirmUploadRequest` | *(xoá - không dùng)* |
| `com.example.media.api.dto.ConfirmUploadResponse` | `com.dts.media.api.response.ConfirmUploadResponse` |
| `com.example.media.domain.Media` | `com.dts.media.domain.entity.Media` |
| `com.example.media.domain.Storage` | `com.dts.media.domain.entity.Storage` |
| `com.example.media.domain.UploadSession` | `com.dts.media.domain.entity.UploadSession` |
| `com.example.media.domain.UploadPolicy` | `com.dts.media.domain.entity.UploadPolicy` |
| `com.example.media.domain.MediaStatus` | `com.dts.media.application.enums.MediaStatus` |
| `com.example.media.domain.UploadSessionStatus` | `com.dts.media.application.enums.UploadSessionStatus` |
| `com.example.media.repository.*` | `com.dts.media.domain.repository.*` |
| `com.example.media.service.UploadService` | `com.dts.media.application.service.UploadService` |
| `com.example.media.config.MinioConfig` | `com.dts.media.infrastructure.configuration.MinioConfiguration` |
| `com.example.media.config.KafkaConfig` | `com.dts.media.infrastructure.configuration.KafkaConfiguration` |
| `com.example.media.config.ConsumerConfig` | `com.dts.media.infrastructure.configuration.KafkaConsumerConfiguration` |
| `com.example.media.message.UploadVerificationMessage` | `com.dts.media.application.dto.UploadVerificationMessage` |
| `com.example.media.MediaUploadApplication` | *(xoá - trùng với MediaApplication.java gốc)* |

---

## Kế hoạch xác minh

### Kiểm tra tự động
- `./gradlew compileJava` — đảm bảo không lỗi biên dịch
- Không còn file nào trong `com/example/`

### Kiểm tra thủ công
- Tất cả import đều dùng `com.dts.media`
- Cấu trúc thư mục khớp 100% với diagram ở trên
