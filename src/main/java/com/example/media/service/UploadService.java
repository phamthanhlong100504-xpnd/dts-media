package com.example.media.service;

import com.example.media.api.dto.*;
import com.example.media.domain.*;
import com.example.media.repository.*;
import com.example.media.config.MinioConfig;
import com.example.media.config.KafkaConfig;
import com.example.media.message.UploadVerificationMessage;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UploadService {

    private final MediaRepository mediaRepository;
    private final UploadSessionRepository uploadSessionRepository;
    private final StorageRepository storageRepository;
    private final UploadPolicyRepository uploadPolicyRepository;
    private final MinioClient minioClient;
    private final KafkaTemplate<String, UploadVerificationMessage> kafkaTemplate;
    private final String presignedUrlExpiry = "15"; // minutes

    @Transactional
    public InitializeUploadResponse initializeUpload(InitializeUploadRequest request, String uploaderId) {
        // 1. Validate policy
        UploadPolicy policy = uploadPolicyRepository.findByTargetType(request.getTargetType())
                .orElseThrow(() -> new IllegalArgumentException("Upload policy not found for target type"));
        if (request.getSizeBytes() > policy.getMaxFileSize()) {
            throw new IllegalArgumentException("File size exceeds policy limit");
        }
        if (!policy.getAllowedMimeTypes().contains(request.getMimeType())) {
            throw new IllegalArgumentException("MIME type not allowed by policy");
        }
        // 2. Default storage
        Storage storage = storageRepository.findByIsDefaultTrue()
                .orElseThrow(() -> new IllegalArgumentException("Default storage not configured"));
        // 3. Create Media
        UUID mediaId = UUID.randomUUID();
        String extension = request.getFileName().contains(".")
                ? request.getFileName().substring(request.getFileName().lastIndexOf('.'))
                : "";
        String objectKey = String.format("uploads/%s/%s%s",
                OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyy/MM/dd")),
                mediaId,
                extension);
        Media media = Media.builder()
                .id(mediaId)
                .objectKey(objectKey)
                .status(MediaStatus.UPLOADING)
                .sizeBytes(request.getSizeBytes())
                .mimeType(request.getMimeType())
                .visibility(request.getVisibility())
                .storageId(storage.getId())
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
        mediaRepository.save(media);
        // 4. Create UploadSession
        UUID sessionId = UUID.randomUUID();
        UploadSession session = UploadSession.builder()
                .id(sessionId)
                .mediaId(mediaId)
                .status(UploadSessionStatus.PENDING)
                .uploaderId(uploaderId)
                .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
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
                            .expiry((int) java.time.Duration.ofMinutes(Long.parseLong(presignedUrlExpiry)).getSeconds())
                            .build()
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate presigned URL", e);
        }
        // 6. Build response
        InitializeUploadResponse response = new InitializeUploadResponse();
        response.setSessionId(sessionId.toString());
        response.setMediaId(mediaId.toString());
        response.setPresignedUrl(presignedUrl);
        response.setObjectKey(objectKey);
        response.setExpiredAt(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(Long.parseLong(presignedUrlExpiry))
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        return response;
    }

    @Transactional
    public ConfirmUploadResponse confirmUpload(UUID sessionId, String uploaderId) {
        UploadSession session = uploadSessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Upload session not found"));
        if (session.getStatus() != UploadSessionStatus.PENDING) {
            throw new IllegalStateException("Upload session not in PENDING state");
        }
        // lock session
        session.setStatus(UploadSessionStatus.UPLOADING);
        session.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        uploadSessionRepository.save(session);
        // publish to Kafka
        UploadVerificationMessage message = new UploadVerificationMessage(sessionId, session.getMediaId());
        kafkaTemplate.send(KafkaConfig.TOPIC_UPLOAD_VERIFICATION, sessionId.toString(), message);
        // build response
        ConfirmUploadResponse response = new ConfirmUploadResponse();
        response.setStatus("UPLOADING");
        response.setSessionId(sessionId.toString());
        return response;
    }
}
