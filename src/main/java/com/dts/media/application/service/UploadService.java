package com.dts.media.application.service;

import com.dts.media.api.form.InitializeUploadForm;
import com.dts.media.api.response.ConfirmUploadResponse;
import com.dts.media.api.response.InitializeUploadResponse;
import com.dts.media.application.dto.UploadVerificationEvent;
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
import com.dts.media.security.MediaAccess;
import com.dts.media.security.MediaJwtVerifier;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.dts.media.infrastructure.configuration.KafkaConfiguration;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Service
public class UploadService {

    private final MediaRepository mediaRepository;
    private final UploadSessionRepository uploadSessionRepository;
    private final StorageRepository storageRepository;
    private final UploadPolicyRepository uploadPolicyRepository;
    private final MinioClient minioClient;
    private final MinioClient internalMinioClient;
    private final KafkaTemplate<String, UploadVerificationMessage> kafkaTemplate;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final MediaStorageGuard storageGuard;

    @Value("${media.url.put-expiry-minutes:15}")
    private int putUrlExpiryMinutes;

    @Value("${kafka.topics.upload-verification:media-upload-verification}")
    private String topicUploadVerification;

    public UploadService(UploadPolicyRepository uploadPolicyRepository,
                         UploadSessionRepository uploadSessionRepository,
                         MediaRepository mediaRepository,
                         StorageRepository storageRepository,
                         MinioClient minioClient,
                         @Qualifier("internalMinioClient") MinioClient internalMinioClient,
                         KafkaTemplate<String, UploadVerificationMessage> kafkaTemplate,
                         ApplicationEventPublisher applicationEventPublisher,
                         MediaStorageGuard storageGuard) {
        this.uploadPolicyRepository = uploadPolicyRepository;
        this.uploadSessionRepository = uploadSessionRepository;
        this.mediaRepository = mediaRepository;
        this.storageRepository = storageRepository;
        this.minioClient = minioClient;
        this.internalMinioClient = internalMinioClient;
        this.kafkaTemplate = kafkaTemplate;
        this.applicationEventPublisher = applicationEventPublisher;
        this.storageGuard = storageGuard;
    }



    @Transactional
    public InitializeUploadResponse initializeUpload(InitializeUploadForm form, String uploaderId) {
        var actor = MediaAccess.require("media:upload");
        if (!"USER".equals(form.getTargetType()) && !actor.manager()) throw new SecurityException("Administrative upload target required");
        UploadPolicy policy = uploadPolicyRepository.findByTargetTypeAndDeletedAtIsNull(form.getTargetType())
                .orElseThrow(() -> new IllegalArgumentException("Upload policy not found for target type"));

        UUID uploaderUuid;
        try {
            uploaderUuid = MediaJwtVerifier.canonicalUuid(uploaderId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid uploader UUID format");
        }

        MediaAccess.actorHint(uploaderUuid, actor);
        if (!policy.isEnabled() || policy.getDeletedAt() != null) throw new IllegalArgumentException("Upload policy disabled");
        UploadChecks.filename(form.getFileName());
        if (form.getSizeBytes() == null || form.getSizeBytes() <= 0 || form.getMimeType() == null || form.getMimeType().isBlank())
            throw new IllegalArgumentException("Invalid upload size or MIME type");
        if (!java.util.Objects.equals(form.getVisibility(), policy.getVisibility())) throw new IllegalArgumentException("Visibility must match upload policy");
        if (MediaAccess.simulation(form.getTargetType())) {
            if (!"PRIVATE".equals(form.getVisibility())) throw new IllegalArgumentException("Simulation uploads must be PRIVATE");
            UploadChecks.simulationFormat(form.getTargetType(), form.getFileName(), form.getMimeType());
        }

        if (policy.getMaxFileSize() != null && form.getSizeBytes() > policy.getMaxFileSize()) {
            throw new IllegalArgumentException("File size exceeds policy limit");
        }

        if ((!UploadChecks.emptyMimeList(policy.getAllowedMimeTypes()) && !UploadChecks.containsMime(policy.getAllowedMimeTypes(), form.getMimeType()))
                || UploadChecks.containsMime(policy.getBlockedMimeTypes(), form.getMimeType()))
            throw new IllegalArgumentException("MIME type not allowed by policy");

        // 2. Default storage
        Storage storage = ("PRIVATE".equals(form.getVisibility())
                ? storageRepository.findByNameAndDeletedAtIsNull("private-minio") : storageRepository.findByIsDefaultTrue())
                .orElseThrow(() -> new IllegalArgumentException("Default storage not configured"));
        if (storage.getDeletedAt() != null || !"ACTIVE".equals(storage.getStatus()))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Upload storage unavailable");
        if ("PRIVATE".equals(form.getVisibility())) storageGuard.requirePrivate(storage);

        // 3. Create Media
        UUID mediaId = UUID.randomUUID();
        String extension = UploadChecks.extension(form.getFileName());

        String mediaType = "OTHER";
        if (form.getMimeType() != null) {
            String primaryType = form.getMimeType().split("/")[0].toUpperCase(java.util.Locale.ROOT);
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
                .uploadTargetType(form.getTargetType())
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
                            .expiry((int) Duration.ofMinutes(putUrlExpiryMinutes).getSeconds())
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
                        .plusMinutes(putUrlExpiryMinutes)
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                .build();
    }

    @Transactional
    public ConfirmUploadResponse confirmUpload(UUID sessionId, String uploaderId) {
        var actor = MediaAccess.require("media:upload");
        MediaAccess.actorHint(MediaJwtVerifier.canonicalUuid(uploaderId), actor);
        UploadSession session = uploadSessionRepository.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Upload session not found"));
        if (!actor.userId().equals(session.getUploaderId())) throw new SecurityException("Upload session access denied");
        if (session.getDeletedAt() != null) throw new IllegalArgumentException("Upload session deleted");
        if (session.getStatus() == UploadSessionStatus.UPLOADING || session.getStatus() == UploadSessionStatus.COMPLETED)
            return ConfirmUploadResponse.builder().sessionId(sessionId.toString()).mediaId(session.getMediaId().toString()).build();
        if (session.getStatus() != UploadSessionStatus.PENDING)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Upload session cannot be confirmed");
        Media targetMedia = mediaRepository.findById(session.getMediaId()).orElseThrow(() -> new IllegalArgumentException("Upload media not found"));
        if (targetMedia.getDeletedAt() != null || !actor.userId().equals(targetMedia.getCreatedBy())) throw new SecurityException("Upload media access denied");
        if (targetMedia.getUploadTargetType() != null && !"USER".equals(targetMedia.getUploadTargetType()) && !actor.manager())
            throw new SecurityException("Administrative upload target required");

        // Lock session
        log.info("Changing session status to UPLOADING for sessionId: {}", sessionId);
        session.setStatus(UploadSessionStatus.UPLOADING);
        session.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        uploadSessionRepository.save(session);

        log.info("Publishing UploadVerificationEvent for sessionId: {}", sessionId);
        // Publish Event (will be sent to Kafka after commit)
        applicationEventPublisher.publishEvent(new UploadVerificationEvent(sessionId, session.getMediaId()));

        // Build response
        return ConfirmUploadResponse.builder()
                .sessionId(sessionId.toString())
                .mediaId(session.getMediaId().toString())
                .build();
    }

    @Transactional
    public void verifyUpload(UUID sessionId, UUID mediaId) {
        log.info("verifyUpload started for sessionId: {}, mediaId: {}", sessionId, mediaId);
        UploadSession session = uploadSessionRepository.findByIdForUpdate(sessionId).orElse(null);
        if (session == null) {
            log.error("verifyUpload failed: Session not found for sessionId: {}", sessionId);
            return;
        }
        if (session.getDeletedAt() != null || !java.util.Objects.equals(mediaId, session.getMediaId())
                || session.getStatus() != UploadSessionStatus.UPLOADING) return;

        Media media = mediaRepository.findById(mediaId).orElse(null);
        if (media == null) {
            log.error("verifyUpload failed: Media not found for mediaId: {}", mediaId);
            session.setStatus(UploadSessionStatus.FAILED);
            session.setFailureReason("Media entity not found");
            session.setFinishedAt(OffsetDateTime.now(ZoneOffset.UTC));
            session.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            uploadSessionRepository.save(session);
            return;
        }

        Storage storage = storageRepository.findById(media.getStorageId()).orElse(null);
        if (storage == null) {
            log.error("verifyUpload failed: Storage not found for storageId: {}", media.getStorageId());
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
            log.info("Calling internalMinioClient.statObject for bucket: {}, objectKey: {}", storage.getBucket(), media.getObjectKey());
            StatObjectResponse stat = internalMinioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(storage.getBucket())
                            .object(media.getObjectKey())
                            .build()
            );

            log.info("statObject success. File size in MinIO: {}, Expected size: {}", stat.size(), media.getSizeBytes());

            boolean simulationMimeMismatch = MediaAccess.simulation(media.getUploadTargetType()) && !media.getMimeType().equals(stat.contentType());
            if (stat.size() != media.getSizeBytes() || simulationMimeMismatch) {
                log.warn("File size mismatch! Updating status to FAILED.");
                session.setStatus(UploadSessionStatus.FAILED);
                session.setFailureReason(simulationMimeMismatch ? "Simulation content type mismatch" : String.format("File size mismatch: expected %d bytes, got %d bytes", media.getSizeBytes(), stat.size()));
                session.setFinishedAt(OffsetDateTime.now(ZoneOffset.UTC));
                session.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
                uploadSessionRepository.save(session);

                media.setStatus(MediaStatus.FAILED);
                media.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
                mediaRepository.save(media);
            } else {
                log.info("File size matched. Updating status to COMPLETED and READY.");
                session.setStatus(UploadSessionStatus.COMPLETED);
                session.setFinishedAt(OffsetDateTime.now(ZoneOffset.UTC));
                session.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
                uploadSessionRepository.save(session);

                media.setStatus(MediaStatus.READY);
                media.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
                mediaRepository.save(media);
            }
            log.info("verifyUpload completed successfully for sessionId: {}", sessionId);
        } catch (Exception e) {
            log.error("verifyUpload exception while accessing MinIO: {}", e.getMessage(), e);
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

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUploadVerificationEvent(UploadVerificationEvent event) {
        log.info("Transaction committed. Sending Kafka message for sessionId: {}", event.getSessionId());
        UploadVerificationMessage message = new UploadVerificationMessage(event.getSessionId(), event.getMediaId());
        kafkaTemplate.send(topicUploadVerification, event.getSessionId().toString(), message);
    }
}
