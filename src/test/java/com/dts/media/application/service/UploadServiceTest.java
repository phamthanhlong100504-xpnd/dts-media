package com.dts.media.application.service;

import com.dts.media.api.form.InitializeUploadForm;
import com.dts.media.api.response.ConfirmUploadResponse;
import com.dts.media.api.response.InitializeUploadResponse;
import com.dts.media.application.dto.UploadVerificationEvent;
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
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UploadServiceTest {

    @Mock
    private MediaRepository mediaRepository;

    @Mock
    private UploadSessionRepository uploadSessionRepository;

    @Mock
    private StorageRepository storageRepository;

    @Mock
    private UploadPolicyRepository uploadPolicyRepository;

    @Mock
    private MinioClient minioClient;

    @Mock
    private MinioClient internalMinioClient;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    @InjectMocks
    private UploadService uploadService;

    private UUID uploaderId;
    private UUID sessionId;
    private UUID mediaId;
    private UUID storageId;
    private UploadPolicy policy;
    private Storage storage;
    private UploadSession session;
    private Media media;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(uploadService, "putUrlExpiryMinutes", 15);
        ReflectionTestUtils.setField(uploadService, "topicUploadVerification", "upload-verification-topic");
        ReflectionTestUtils.setField(uploadService, "minioClient", minioClient);
        ReflectionTestUtils.setField(uploadService, "internalMinioClient", internalMinioClient);

        uploaderId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
        mediaId = UUID.randomUUID();
        storageId = UUID.randomUUID();

        policy = UploadPolicy.builder()
                .targetType("AVATAR")
                .maxFileSize(10000000L)
                .build();

        storage = Storage.builder()
                .id(storageId)
                .bucket("dts-bucket")
                .endpoint("http://localhost:9000")
                .isDefault(true)
                .build();

        media = Media.builder()
                .id(mediaId)
                .storageId(storageId)
                .sizeBytes(1024L)
                .status(MediaStatus.UPLOADING)
                .objectKey("test-object-key")
                .build();

        session = UploadSession.builder()
                .id(sessionId)
                .mediaId(mediaId)
                .uploaderId(uploaderId)
                .status(UploadSessionStatus.PENDING)
                .build();
    }

    // ==========================================
    // initializeUpload
    // ==========================================

    @Test
    @DisplayName("initializeUpload - Path 1: Invalid Uploader UUID")
    void initializeUpload_InvalidUploaderId() {
        InitializeUploadForm form = new InitializeUploadForm();
        form.setTargetType("AVATAR");

        when(uploadPolicyRepository.findByTargetType("AVATAR")).thenReturn(Optional.of(policy));

        assertThrows(IllegalArgumentException.class, () -> uploadService.initializeUpload(form, "invalid-uuid"));
    }

    @Test
    @DisplayName("initializeUpload - Path 2: Exceeds Size Limit")
    void initializeUpload_ExceedsSizeLimit() {
        InitializeUploadForm form = new InitializeUploadForm();
        form.setTargetType("AVATAR");
        form.setSizeBytes(20000000L); // Limit is 10M

        policy.setMaxFileSize(10000000L);
        when(uploadPolicyRepository.findByTargetType("AVATAR")).thenReturn(Optional.of(policy));

        assertThrows(IllegalArgumentException.class, () -> uploadService.initializeUpload(form, uploaderId.toString()));
    }

    @Test
    @DisplayName("initializeUpload - Path 3: Happy Case")
    void initializeUpload_HappyCase() throws Exception {
        InitializeUploadForm form = new InitializeUploadForm();
        form.setTargetType("AVATAR");
        form.setSizeBytes(1024L);
        form.setFileName("test.png");
        form.setMimeType("image/png");

        when(uploadPolicyRepository.findByTargetType("AVATAR")).thenReturn(Optional.of(policy));
        when(storageRepository.findByIsDefaultTrue()).thenReturn(Optional.of(storage));
        when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class))).thenReturn("http://presigned-url");

        InitializeUploadResponse response = uploadService.initializeUpload(form, uploaderId.toString());

        assertNotNull(response);
        assertNotNull(response.getSessionId());
        assertNotNull(response.getMediaId());
        assertEquals("http://presigned-url", response.getPresignedUrl());
        verify(mediaRepository).save(any(Media.class));
        verify(uploadSessionRepository).save(any(UploadSession.class));
    }

    // ==========================================
    // confirmUpload
    // ==========================================

    @Test
    @DisplayName("confirmUpload - Path 1: Session Not Found")
    void confirmUpload_SessionNotFound() {
        when(uploadSessionRepository.findById(sessionId)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> uploadService.confirmUpload(sessionId, uploaderId.toString()));
    }

    @Test
    @DisplayName("confirmUpload - Path 2: Not PENDING")
    void confirmUpload_NotPending() {
        session.setStatus(UploadSessionStatus.UPLOADING);
        when(uploadSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        assertThrows(IllegalStateException.class, () -> uploadService.confirmUpload(sessionId, uploaderId.toString()));
    }

    @Test
    @DisplayName("confirmUpload - Path 3: Happy Case")
    void confirmUpload_HappyCase() {
        when(uploadSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        ConfirmUploadResponse response = uploadService.confirmUpload(sessionId, uploaderId.toString());

        assertNotNull(response);
        assertEquals(sessionId.toString(), response.getSessionId());
        assertEquals(mediaId.toString(), response.getMediaId());
        assertEquals(UploadSessionStatus.UPLOADING, session.getStatus());
        verify(uploadSessionRepository).save(session);
        verify(applicationEventPublisher).publishEvent(any(UploadVerificationEvent.class));
    }

    // ==========================================
    // verifyUpload
    // ==========================================

    @Test
    @DisplayName("verifyUpload - Path 1: Session Not Found")
    void verifyUpload_SessionNotFound() {
        when(uploadSessionRepository.findById(sessionId)).thenReturn(Optional.empty());
        uploadService.verifyUpload(sessionId, mediaId);
        verify(mediaRepository, never()).findById(any());
    }

    @Test
    @DisplayName("verifyUpload - Path 2: Media Not Found")
    void verifyUpload_MediaNotFound() {
        when(uploadSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(mediaRepository.findById(mediaId)).thenReturn(Optional.empty());

        uploadService.verifyUpload(sessionId, mediaId);

        assertEquals(UploadSessionStatus.FAILED, session.getStatus());
        verify(uploadSessionRepository).save(session);
    }

    @Test
    @DisplayName("verifyUpload - Path 3: Exception accessing MinIO")
    void verifyUpload_MinioException() throws Exception {
        when(uploadSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(mediaRepository.findById(mediaId)).thenReturn(Optional.of(media));
        when(storageRepository.findById(storageId)).thenReturn(Optional.of(storage));
        when(internalMinioClient.statObject(any(StatObjectArgs.class))).thenThrow(new RuntimeException("MinIO Error"));

        uploadService.verifyUpload(sessionId, mediaId);

        assertEquals(UploadSessionStatus.FAILED, session.getStatus());
        assertEquals(MediaStatus.FAILED, media.getStatus());
        verify(uploadSessionRepository).save(session);
        verify(mediaRepository).save(media);
    }

    @Test
    @DisplayName("verifyUpload - Path 4: Happy Case (Size Matched)")
    void verifyUpload_HappyCase() throws Exception {
        when(uploadSessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
        when(mediaRepository.findById(mediaId)).thenReturn(Optional.of(media));
        when(storageRepository.findById(storageId)).thenReturn(Optional.of(storage));
        
        StatObjectResponse statResponse = mock(StatObjectResponse.class);
        when(statResponse.size()).thenReturn(1024L);
        when(internalMinioClient.statObject(any(StatObjectArgs.class))).thenReturn(statResponse);

        uploadService.verifyUpload(sessionId, mediaId);

        assertEquals(UploadSessionStatus.COMPLETED, session.getStatus());
        assertEquals(MediaStatus.READY, media.getStatus());
        verify(uploadSessionRepository).save(session);
        verify(mediaRepository).save(media);
    }
}
