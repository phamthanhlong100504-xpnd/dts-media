package com.dts.media.application.service;

import com.dts.media.api.request.UpdateMediaRequest;
import com.dts.media.api.response.MediaResponse;
import com.dts.media.application.enums.MediaStatus;
import com.dts.media.domain.entity.Media;
import com.dts.media.domain.entity.Storage;
import com.dts.media.domain.repository.MediaRepository;
import com.dts.media.domain.repository.StorageRepository;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MediaServiceTest {

    @Mock
    private MediaRepository mediaRepository;

    @Mock
    private StorageRepository storageRepository;

    @Mock
    private MinioClient minioClient;

    @InjectMocks
    private MediaService mediaService;

    private UUID mediaId;
    private UUID userId;
    private UUID storageId;
    private Media media;
    private Storage storage;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(mediaService, "getUrlExpiryMinutes", 60);

        mediaId = UUID.randomUUID();
        userId = UUID.randomUUID();
        storageId = UUID.randomUUID();

        media = Media.builder()
                .id(mediaId)
                .storageId(storageId)
                .originalFilename("test-file.png")
                .extension("png")
                .mimeType("image/png")
                .sizeBytes(1024L)
                .mediaType("IMAGE")
                .visibility("PUBLIC")
                .status(MediaStatus.READY)
                .objectKey("2026/08/20/test-file.png")
                .createdBy(userId)
                .createdAt(OffsetDateTime.now())
                .build();

        storage = Storage.builder()
                .id(storageId)
                .bucket("dts-bucket")
                .endpoint("http://localhost:9000")
                .build();
    }

    // ==========================================
    // getMediaDetail
    // ==========================================

    @Test
    @DisplayName("getMediaDetail - Path 1: Not Found")
    void getMediaDetail_NotFound() {
        when(mediaRepository.findById(mediaId)).thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> mediaService.getMediaDetail(mediaId));
        assertTrue(ex.getMessage().contains("Media not found"));
    }

    @Test
    @DisplayName("getMediaDetail - Path 2: Deleted")
    void getMediaDetail_Deleted() {
        media.setDeletedAt(OffsetDateTime.now());
        when(mediaRepository.findById(mediaId)).thenReturn(Optional.of(media));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> mediaService.getMediaDetail(mediaId));
        assertTrue(ex.getMessage().contains("Media has been deleted"));
    }

    @Test
    @DisplayName("getMediaDetail - Path 3: Status Not READY")
    void getMediaDetail_NotReady() {
        media.setStatus(MediaStatus.UPLOADING);
        when(mediaRepository.findById(mediaId)).thenReturn(Optional.of(media));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> mediaService.getMediaDetail(mediaId));
        assertTrue(ex.getMessage().contains("Media is not ready for access"));
    }

    @Test
    @DisplayName("getMediaDetail - Path 4: Storage Not Found")
    void getMediaDetail_StorageNotFound() {
        when(mediaRepository.findById(mediaId)).thenReturn(Optional.of(media));
        when(storageRepository.findById(storageId)).thenReturn(Optional.empty());

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> mediaService.getMediaDetail(mediaId));
        assertTrue(ex.getMessage().contains("Storage configuration not found"));
    }

    @Test
    @DisplayName("getMediaDetail - Path 5: Happy Case (Generates URL)")
    void getMediaDetail_HappyCase() throws Exception {
        when(mediaRepository.findById(mediaId)).thenReturn(Optional.of(media));
        when(storageRepository.findById(storageId)).thenReturn(Optional.of(storage));
        when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn("http://localhost:9000/dts-bucket/2026/08/20/test-file.png?expire=123");

        MediaResponse response = mediaService.getMediaDetail(mediaId);

        assertNotNull(response);
        assertEquals(mediaId.toString(), response.getMediaId());
        assertEquals("test-file.png", response.getOriginalFilename());
        assertEquals("http://localhost:9000/dts-bucket/2026/08/20/test-file.png?expire=123", response.getUrl());
    }

    // ==========================================
    // getMediaList
    // ==========================================

    @Test
    @DisplayName("getMediaList - Happy Case")
    void getMediaList_HappyCase() throws Exception {
        PageRequest pageable = PageRequest.of(0, 10);
        Page<Media> mediaPage = new PageImpl<>(List.of(media), pageable, 1);

        when(mediaRepository.findMediasByFilters(userId, "IMAGE", MediaStatus.READY, "PUBLIC", pageable))
                .thenReturn(mediaPage);
        when(storageRepository.findById(storageId)).thenReturn(Optional.of(storage));
        when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn("http://presigned-url");

        Page<MediaResponse> responsePage = mediaService.getMediaList(userId, "IMAGE", MediaStatus.READY, "PUBLIC", pageable);

        assertNotNull(responsePage);
        assertEquals(1, responsePage.getTotalElements());
        assertEquals("http://presigned-url", responsePage.getContent().get(0).getUrl());
    }

    // ==========================================
    // updateMedia
    // ==========================================

    @Test
    @DisplayName("updateMedia - Path 1: Not Found")
    void updateMedia_NotFound() {
        when(mediaRepository.findById(mediaId)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> mediaService.updateMedia(mediaId, userId, new UpdateMediaRequest()));
    }

    @Test
    @DisplayName("updateMedia - Path 2: Deleted")
    void updateMedia_Deleted() {
        media.setDeletedAt(OffsetDateTime.now());
        when(mediaRepository.findById(mediaId)).thenReturn(Optional.of(media));

        assertThrows(IllegalArgumentException.class, () -> mediaService.updateMedia(mediaId, userId, new UpdateMediaRequest()));
    }

    @Test
    @DisplayName("updateMedia - Path 3: Security Exception")
    void updateMedia_SecurityException() {
        when(mediaRepository.findById(mediaId)).thenReturn(Optional.of(media));
        UUID wrongUserId = UUID.randomUUID();

        assertThrows(SecurityException.class, () -> mediaService.updateMedia(mediaId, wrongUserId, new UpdateMediaRequest()));
    }

    @Test
    @DisplayName("updateMedia - Path 4: Happy Case")
    void updateMedia_HappyCase() {
        UpdateMediaRequest request = new UpdateMediaRequest();
        request.setVisibility("PRIVATE");
        request.setOriginalFilename("new-name.png");

        when(mediaRepository.findById(mediaId)).thenReturn(Optional.of(media));
        when(mediaRepository.save(any(Media.class))).thenReturn(media);

        MediaResponse response = mediaService.updateMedia(mediaId, userId, request);

        assertNotNull(response);
        assertEquals("PRIVATE", media.getVisibility());
        assertEquals("new-name.png", media.getOriginalFilename());
        assertNotNull(media.getUpdatedAt());
        verify(mediaRepository).save(media);
    }

    // ==========================================
    // deleteMedia
    // ==========================================

    @Test
    @DisplayName("deleteMedia - Path 1: Not Found")
    void deleteMedia_NotFound() {
        when(mediaRepository.findById(mediaId)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> mediaService.deleteMedia(mediaId, userId));
    }

    @Test
    @DisplayName("deleteMedia - Path 2: Deleted")
    void deleteMedia_Deleted() {
        media.setDeletedAt(OffsetDateTime.now());
        when(mediaRepository.findById(mediaId)).thenReturn(Optional.of(media));

        assertThrows(IllegalArgumentException.class, () -> mediaService.deleteMedia(mediaId, userId));
    }

    @Test
    @DisplayName("deleteMedia - Path 3: Security Exception")
    void deleteMedia_SecurityException() {
        when(mediaRepository.findById(mediaId)).thenReturn(Optional.of(media));
        UUID wrongUserId = UUID.randomUUID();

        assertThrows(SecurityException.class, () -> mediaService.deleteMedia(mediaId, wrongUserId));
    }

    @Test
    @DisplayName("deleteMedia - Path 4: Happy Case")
    void deleteMedia_HappyCase() {
        when(mediaRepository.findById(mediaId)).thenReturn(Optional.of(media));
        
        mediaService.deleteMedia(mediaId, userId);

        assertNotNull(media.getDeletedAt());
        assertEquals(userId, media.getUpdatedBy());
        verify(mediaRepository).save(media);
    }
}
