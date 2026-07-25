package com.dts.media.application.service;

import com.dts.media.api.form.InitializeUploadForm;
import com.dts.media.api.response.ConfirmUploadResponse;
import com.dts.media.api.response.InitializeUploadResponse;
import com.dts.media.application.dto.UploadVerificationMessage;
import com.dts.media.application.enums.MediaStatus;
import com.dts.media.application.enums.UploadSessionStatus;
import com.dts.media.domain.entity.Media;
import com.dts.media.domain.entity.Storage;
import com.dts.media.domain.entity.UploadPolicy;
import com.dts.media.domain.entity.UploadSession;
import com.dts.media.domain.repository.MediaRepository;
import com.dts.media.domain.repository.StorageRepository;
import com.dts.media.domain.repository.UploadPolicyRepository;
import com.dts.media.domain.repository.UploadSessionRepository;
import com.dts.media.infrastructure.configuration.KafkaConfiguration;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UploadService {

    private static final int PRESIGNED_URL_EXPIRY_MINUTES = 15;

    private final MediaRepository mediaRepository;
    private final UploadSessionRepository uploadSessionRepository;
    private final StorageRepository storageRepository;
    private final UploadPolicyRepository uploadPolicyRepository;
    private final MinioClient minioClient;
    private final KafkaTemplate<String, UploadVerificationMessage> kafkaTemplate;

    @Transactional
    public InitializeUploadResponse initializeUpload(InitializeUploadForm form, String uploaderId) {
        UploadPolicy policy = uploadPolicyRepository.findByTargetType(form.getTargetType())
                .orElseThrow(() -> new IllegalArgumentException("Upload policy not found for target type"));

        UUID uploaderUuid;
        try {
            uploaderUuid = UUID.fromString(uploaderId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid uploader UUID format");
        }

        if (policy.getMaxFileSize() != null && form.getSizeBytes() > policy.getMaxFileSize()) {
            throw new IllegalArgumentException("File size exceeds policy limit");
        }

        if (policy.getAllowedMimeTypes() != null && !policy.getAllowedMimeTypes().isEmpty() && !policy.getAllowedMimeTypes().equals("[]")) {
            if (!policy.getAllowedMimeTypes().contains(form.getMimeType())) {
                throw new IllegalArgumentException("MIME type not allowed by policy");
            }
        }

        // 2. Default storage
        Storage storage = storageRepository.findByIsDefaultTrue()
                .orElseThrow(() -> new IllegalArgumentException("Default storage not configured"));

        // 3. Create Media
        UUID mediaId = UUID.randomUUID();
        String extension = "";
        if (form.getFileName() != null && form.getFileName().contains(".")) {
            extension = form.getFileName().substring(form.getFileName().lastIndexOf('.') + 1);
        }

        String mediaType = "OTHER";
        if (form.getMimeType() != null) {
            String primaryType = form.getMimeType().split("/")[0].toUpperCase();
            if (primaryType.equals("IMAGE") || primaryType.equals("VIDEO") || primaryType.equals("AUDIO")) {
                mediaType = primaryType;
            } else if (form.getMimeType().contains("pdf") || form.getMimeType().contains("word") || form.getMimeType().contains("excel") || form.getMimeType().contains("powerpoint") || form.getMimeType().contains("office")) {
                mediaType = "DOCUMENT";
            } else if (form.getMimeType().contains("zip") || form.getMimeType().contains("rar") || form.getMimeType().contains("tar") || form.getMimeType().contains("gzip")) {
                mediaType = "ARCHIVE";
            }
        }

        String objectKey = String.format("uploads/%s/%s%s",
                OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy/MM/dd")),
                mediaId,
                extension.isEmpty() ? "" : "." + extension);

        Media media = Media.builder()
                .id(mediaId)
                .storageId(storage.getId())
                .objectKey(objectKey)
                .originalFilename(form.getFileName())
                .extension(extension)
                .mimeType(form.getMimeType())
                .sizeBytes(form.getSizeBytes())
                .mediaType(mediaType)
                .visibility(form.getVisibility())
                .status(MediaStatus.UPLOADING)
                .metadata("{}")
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .updatedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .createdBy(uploaderUuid)
                .build();
        mediaRepository.save(media);

        // 4. Create UploadSession
        UUID sessionId = UUID.randomUUID();
        UploadSession session = UploadSession.builder()
                .id(sessionId)
                .mediaId(mediaId)
                .uploaderId(uploaderUuid)
                .uploaderType("USER")
                .status(UploadSessionStatus.PENDING)
                .metadata("{}")
                .startedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .updatedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .createdBy(uploaderUuid)
                .build();
        uploadSessionRepository.save(session);

        // 5. Generate presigned URL
        String presignedUrl;
        try {
            presignedUrl = minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.PUT)
                            .bucket(storage.getBucket())
                            .object(objectKey)
                            .expiry((int) Duration.ofMinutes(PRESIGNED_URL_EXPIRY_MINUTES).getSeconds())
                            .build()
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate presigned URL", e);
        }

        // 6. Build response
        return InitializeUploadResponse.builder()
                .sessionId(sessionId.toString())
                .mediaId(mediaId.toString())
                .presignedUrl(presignedUrl)
                .objectKey(objectKey)
                .expiredAt(OffsetDateTime.now(ZoneOffset.UTC)
                        .plusMinutes(PRESIGNED_URL_EXPIRY_MINUTES)
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                .build();
    }

    @Transactional
    public ConfirmUploadResponse confirmUpload(UUID sessionId, String uploaderId) {
        UploadSession session = uploadSessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Upload session not found"));

        if (session.getStatus() != UploadSessionStatus.PENDING) {
            throw new IllegalStateException("Upload session not in PENDING state");
        }

        // Lock session
        session.setStatus(UploadSessionStatus.UPLOADING);
        session.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        uploadSessionRepository.save(session);

        // Publish to Kafka
        UploadVerificationMessage message = new UploadVerificationMessage(sessionId, session.getMediaId());
        kafkaTemplate.send(KafkaConfiguration.TOPIC_UPLOAD_VERIFICATION, sessionId.toString(), message);

        // Build response
        return ConfirmUploadResponse.builder()
                .sessionId(sessionId.toString())
                .mediaId(session.getMediaId().toString())
                .build();
    }

    @Transactional
    public void verifyUpload(UUID sessionId, UUID mediaId) {
        UploadSession session = uploadSessionRepository.findById(sessionId).orElse(null);
        if (session == null) {
            return;
        }

        Media media = mediaRepository.findById(mediaId).orElse(null);
        if (media == null) {
            session.setStatus(UploadSessionStatus.FAILED);
            session.setFailureReason("Media entity not found");
            session.setFinishedAt(OffsetDateTime.now(ZoneOffset.UTC));
            session.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            uploadSessionRepository.save(session);
            return;
        }

        Storage storage = storageRepository.findById(media.getStorageId()).orElse(null);
        if (storage == null) {
            session.setStatus(UploadSessionStatus.FAILED);
            session.setFailureReason("Storage configuration not found");
            session.setFinishedAt(OffsetDateTime.now(ZoneOffset.UTC));
            session.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            uploadSessionRepository.save(session);

            media.setStatus(MediaStatus.FAILED);
            media.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            mediaRepository.save(media);
            return;
        }

        try {
            StatObjectResponse stat = minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(storage.getBucket())
                            .object(media.getObjectKey())
                            .build()
            );

            if (stat.size() != media.getSizeBytes()) {
                session.setStatus(UploadSessionStatus.FAILED);
                session.setFailureReason(String.format("File size mismatch: expected %d bytes, got %d bytes", media.getSizeBytes(), stat.size()));
                session.setFinishedAt(OffsetDateTime.now(ZoneOffset.UTC));
                session.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
                uploadSessionRepository.save(session);

                media.setStatus(MediaStatus.FAILED);
                media.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
                mediaRepository.save(media);
            } else {
                session.setStatus(UploadSessionStatus.COMPLETED);
                session.setFinishedAt(OffsetDateTime.now(ZoneOffset.UTC));
                session.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
                uploadSessionRepository.save(session);

                media.setStatus(MediaStatus.READY);
                media.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
                mediaRepository.save(media);
            }
        } catch (Exception e) {
            session.setStatus(UploadSessionStatus.FAILED);
            session.setFailureReason("Object not found or inaccessible on storage provider: " + e.getMessage());
            session.setFinishedAt(OffsetDateTime.now(ZoneOffset.UTC));
            session.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            uploadSessionRepository.save(session);

            media.setStatus(MediaStatus.FAILED);
            media.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            mediaRepository.save(media);
        }
    }
}
