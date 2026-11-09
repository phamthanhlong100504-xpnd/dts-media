package com.dts.media.application.service;

import com.dts.media.api.request.UpdateMediaRequest;
import com.dts.media.api.response.MediaResponse;
import com.dts.media.application.enums.MediaStatus;
import com.dts.media.domain.entity.Media;
import com.dts.media.domain.entity.Storage;
import com.dts.media.domain.repository.MediaRepository;
import com.dts.media.domain.repository.StorageRepository;
import com.dts.media.security.MediaAccess;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MediaService {
    @Value("${media.url.get-expiry-minutes:60}") private int getUrlExpiryMinutes;
    private final MediaRepository mediaRepository;
    private final StorageRepository storageRepository;
    private final MinioClient minioClient;
    private final MediaStorageGuard storageGuard;

    @Transactional(readOnly = true)
    public MediaResponse getMediaDetail(UUID mediaId) {
        Media media = findActive(mediaId);
        boolean publicReady = "PUBLIC".equals(media.getVisibility()) && media.getStatus() == MediaStatus.READY
                && !MediaAccess.simulation(media.getUploadTargetType());
        if (!publicReady) MediaAccess.ownerOrManager(media, MediaAccess.require("media:read"));
        // Owners/admin can poll actual UPLOADING/FAILED metadata without a URL.
        return response(media, media.getStatus() == MediaStatus.READY ? sign(media, regularExpiry()) : null);
    }
    @Transactional(readOnly = true)
    public Page<MediaResponse> getMediaList(UUID userId, String mediaType, MediaStatus status, String visibility, Pageable pageable) {
        var actor = MediaAccess.require("media:read");
        if (!actor.manager()) MediaAccess.actorHint(userId, actor);
        UUID scope = actor.manager() ? userId : actor.userId();
        return mediaRepository.findMediasByFilters(scope, mediaType, status, visibility, pageable)
                .map(media -> response(media, media.getStatus() == MediaStatus.READY && "PUBLIC".equals(media.getVisibility())
                        && !MediaAccess.simulation(media.getUploadTargetType()) ? sign(media, regularExpiry()) : null));
    }
    @Transactional
    public MediaResponse updateMedia(UUID mediaId, UUID userId, UpdateMediaRequest request) {
        var actor = MediaAccess.require("media:update");
        MediaAccess.actorHint(userId, actor);
        Media media = findActive(mediaId); MediaAccess.ownerOrManager(media, actor);
        if (request.getVisibility() != null) {
            if (!request.getVisibility().equals("PUBLIC") && !request.getVisibility().equals("PRIVATE"))
                throw new IllegalArgumentException("Invalid visibility");
            if (MediaAccess.simulation(media.getUploadTargetType()) && !request.getVisibility().equals("PRIVATE"))
                throw new IllegalArgumentException("Simulation media must remain PRIVATE");
            media.setVisibility(request.getVisibility());
        }
        if (request.getOriginalFilename() != null) {
            UploadChecks.filename(request.getOriginalFilename());
            if (MediaAccess.simulation(media.getUploadTargetType()))
                UploadChecks.simulationFormat(media.getUploadTargetType(), request.getOriginalFilename(), media.getMimeType());
            media.setOriginalFilename(request.getOriginalFilename());
        }
        media.setUpdatedAt(OffsetDateTime.now()); media.setUpdatedBy(actor.userId());
        return response(mediaRepository.save(media), null);
    }
    @Transactional
    public void deleteMedia(UUID mediaId, UUID userId) {
        var actor = MediaAccess.require("media:delete"); MediaAccess.actorHint(userId, actor);
        Media media = findActive(mediaId); MediaAccess.ownerOrManager(media, actor);
        media.setDeletedAt(OffsetDateTime.now()); media.setUpdatedBy(actor.userId());
        mediaRepository.save(media);
    }
    Media findActive(UUID id) {
        Media media = mediaRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("Media not found: " + id));
        if (media.getDeletedAt() != null) throw new IllegalArgumentException("Media has been deleted: " + id);
        return media;
    }
    private int regularExpiry() {
        long seconds = Duration.ofMinutes(getUrlExpiryMinutes).getSeconds();
        if (seconds < 1 || seconds > 604800) throw new IllegalStateException("Invalid media URL expiry configuration");
        return (int) seconds;
    }
    String sign(Media media, int seconds) {
        Storage storage = storageRepository.findById(media.getStorageId())
                .orElseThrow(() -> new IllegalStateException("Storage configuration not found for media"));
        if (storage.getDeletedAt() != null || !"ACTIVE".equals(storage.getStatus()))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Media storage unavailable");
        if ("PRIVATE".equals(media.getVisibility()) || MediaAccess.simulation(media.getUploadTargetType())) storageGuard.requirePrivate(storage);
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().method(Method.GET)
                    .bucket(storage.getBucket()).object(media.getObjectKey()).expiry(seconds).build());
        } catch (Exception failure) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Media URL unavailable");
        }
    }
    private MediaResponse response(Media media, String url) {
        return MediaResponse.builder().mediaId(media.getId().toString()).originalFilename(media.getOriginalFilename())
                .extension(media.getExtension()).mimeType(media.getMimeType()).sizeBytes(media.getSizeBytes())
                .mediaType(media.getMediaType()).visibility(media.getVisibility()).status(media.getStatus().name())
                .url(url).createdAt(media.getCreatedAt()).build();
    }
}
