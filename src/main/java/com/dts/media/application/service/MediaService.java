package com.dts.media.application.service;

import com.dts.media.api.response.MediaResponse;
import com.dts.media.application.enums.MediaStatus;
import com.dts.media.domain.entity.Media;
import com.dts.media.domain.entity.Storage;
import com.dts.media.domain.repository.MediaRepository;
import com.dts.media.domain.repository.StorageRepository;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MediaService {

    private static final int GET_URL_EXPIRY_MINUTES = 60;

    private final MediaRepository mediaRepository;
    private final StorageRepository storageRepository;
    private final MinioClient minioClient;

    @Transactional(readOnly = true)
    public MediaResponse getMediaDetail(UUID mediaId) {
        Media media = mediaRepository.findById(mediaId)
                .orElseThrow(() -> new IllegalArgumentException("Media not found: " + mediaId));

        if (media.getDeletedAt() != null) {
            throw new IllegalArgumentException("Media has been deleted: " + mediaId);
        }

        if (media.getStatus() != MediaStatus.READY) {
            throw new IllegalStateException("Media is not ready for access. Current status: " + media.getStatus());
        }

        Storage storage = storageRepository.findById(media.getStorageId())
                .orElseThrow(() -> new IllegalStateException("Storage configuration not found for media"));

        String downloadUrl;
        try {
            downloadUrl = minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(storage.getBucket())
                            .object(media.getObjectKey())
                            .expiry((int) Duration.ofMinutes(GET_URL_EXPIRY_MINUTES).getSeconds())
                            .build()
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate presigned GET URL", e);
        }

        return MediaResponse.builder()
                .mediaId(media.getId().toString())
                .originalFilename(media.getOriginalFilename())
                .extension(media.getExtension())
                .mimeType(media.getMimeType())
                .sizeBytes(media.getSizeBytes())
                .mediaType(media.getMediaType())
                .visibility(media.getVisibility())
                .status(media.getStatus().name())
                .url(downloadUrl)
                .createdAt(media.getCreatedAt())
                .build();
    }
}
