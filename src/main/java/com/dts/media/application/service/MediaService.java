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
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import com.dts.media.api.request.UpdateMediaRequest;
import org.springframework.beans.factory.annotation.Value;

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

        String downloadUrl = null;
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
            System.err.println("Failed to generate presigned GET URL: " + e.getMessage());
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

    @Transactional(readOnly = true)
    public Page<MediaResponse> getMediaList(UUID userId, String mediaType, MediaStatus status, String visibility, Pageable pageable) {
        Page<Media> medias = mediaRepository.findMediasByFilters(userId, mediaType, status, visibility, pageable);
        return medias.map(media -> {
            String downloadUrl = null;
            if (media.getStatus() == MediaStatus.READY) {
                try {
                    Storage storage = storageRepository.findById(media.getStorageId())
                            .orElseThrow(() -> new IllegalStateException("Storage configuration not found"));
                    downloadUrl = minioClient.getPresignedObjectUrl(
                            GetPresignedObjectUrlArgs.builder()
                                    .method(Method.GET)
                                    .bucket(storage.getBucket())
                                    .object(media.getObjectKey())
                                    .expiry((int) Duration.ofMinutes(GET_URL_EXPIRY_MINUTES).getSeconds())
                                    .build()
                    );
                } catch (Exception e) {
                    // Ignore or log error
                }
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
        });
    }

    @Transactional
    public MediaResponse updateMedia(UUID mediaId, UUID userId, UpdateMediaRequest request) {
        Media media = mediaRepository.findById(mediaId)
                .orElseThrow(() -> new IllegalArgumentException("Media not found: " + mediaId));
        if (media.getDeletedAt() != null) {
            throw new IllegalArgumentException("Media has been deleted: " + mediaId);
        }
        if (userId != null && !media.getCreatedBy().equals(userId)) {
            throw new SecurityException("No permission to update this media");
        }
        if (request.getVisibility() != null) {
            media.setVisibility(request.getVisibility());
        }
        if (request.getOriginalFilename() != null) {
            media.setOriginalFilename(request.getOriginalFilename());
        }
        media.setUpdatedAt(OffsetDateTime.now());
        media = mediaRepository.save(media);
        
        return MediaResponse.builder()
                .mediaId(media.getId().toString())
                .originalFilename(media.getOriginalFilename())
                .extension(media.getExtension())
                .mimeType(media.getMimeType())
                .sizeBytes(media.getSizeBytes())
                .mediaType(media.getMediaType())
                .visibility(media.getVisibility())
                .status(media.getStatus().name())
                .url(null) // API Update không cần sinh lại URL tải file để tối ưu hiệu năng
                .createdAt(media.getCreatedAt())
                .build();
    }

    @Transactional
    public void deleteMedia(UUID mediaId, UUID userId) {
        Media media = mediaRepository.findById(mediaId)
                .orElseThrow(() -> new IllegalArgumentException("Media not found: " + mediaId));
        if (media.getDeletedAt() != null) {
            throw new IllegalArgumentException("Media has been deleted: " + mediaId);
        }
        if (userId != null && !media.getCreatedBy().equals(userId)) {
            throw new SecurityException("No permission to delete this media");
        }
        media.setDeletedAt(OffsetDateTime.now());
        media.setUpdatedBy(userId);
        mediaRepository.save(media);
    }
}
