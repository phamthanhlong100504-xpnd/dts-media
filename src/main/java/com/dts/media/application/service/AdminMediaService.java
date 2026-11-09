package com.dts.media.application.service;

import com.dts.media.api.response.MediaResponse;
import com.dts.media.application.enums.MediaStatus;
import com.dts.media.domain.entity.Media;
import com.dts.media.domain.repository.MediaRepository;
import com.dts.media.security.MediaAccess;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * BE02 stage 1: secure media metadata inspection. Reference discovery is deliberately
 * fail-closed until Content Builder and historical consumers publish complete references.
 * This class never modifies or retires a Media object.
 */
@Service
public class AdminMediaService {
    private final MediaRepository mediaRepository;
    private final MediaService mediaService;

    public AdminMediaService(MediaRepository mediaRepository, MediaService mediaService) {
        this.mediaRepository = mediaRepository;
        this.mediaService = mediaService;
    }

    private static void requireManager() {
        var actor = MediaAccess.require("media:read");
        if (!actor.manager()) throw new SecurityException("Admin media management permission required");
    }

    private static AdminMediaItem item(Media media) {
        return new AdminMediaItem(media.getId(), media.getMediaType(), media.getUploadTargetType(),
            media.getOriginalFilename(), media.getMimeType(), media.getSizeBytes(),
            media.getStatus().name(), media.getVisibility(), media.getCreatedBy(),
            media.getCreatedAt(), null, false, "REFERENCES_NOT_VERIFIED");
    }

    @Transactional(readOnly = true)
    public Page<AdminMediaItem> list(UUID createdBy, String mediaType, MediaStatus status,
                                      String visibility, int page, int size) {
        requireManager();
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Page or size out of range");
        return mediaRepository.findMediasByFilters(createdBy, mediaType, status, visibility,
            PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")))
            .map(AdminMediaService::item);
    }

    @Transactional(readOnly = true)
    public AdminMediaItem detail(UUID mediaId) {
        requireManager();
        Media media = mediaService.findActive(mediaId);
        return item(media);
    }

    @Transactional(readOnly = true)
    public MediaReferences references(UUID mediaId) {
        requireManager();
        Media media = mediaService.findActive(mediaId);
        // An empty list here does NOT mean no references: only the full cross-service
        // registry may authoritatively assert that a Media is unreferenced.
        return new MediaReferences(media.getId(), false, null, List.of(), false,
            "REFERENCES_NOT_VERIFIED");
    }

    @Transactional(readOnly = true)
    public MediaResponse preview(UUID mediaId) {
        requireManager();
        Media media = mediaService.findActive(mediaId);
        if (media.getStatus() != MediaStatus.READY)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "MEDIA_NOT_READY");
        // Presigning happens only for READY media. Never persist a signed URL.
        return mediaService.getMediaDetail(mediaId);
    }

    public void retire(UUID mediaId) {
        requireManager();
        mediaService.findActive(mediaId);
        // Old media may be referenced by sessions/exams outside this service. Without
        // complete coverage, deletion cannot be proven safe and MUST be denied.
        throw new ResponseStatusException(HttpStatus.CONFLICT, "REFERENCES_NOT_VERIFIED");
    }

    public record AdminMediaItem(UUID mediaId, String mediaType, String targetType,
                                 String originalFilename, String mimeType, Long sizeBytes,
                                 String status, String visibility, UUID createdBy,
                                 OffsetDateTime createdAt, Long referenceCount,
                                 boolean canRetire, String reasonCode) {}

    public record MediaReferences(UUID mediaId, boolean verified, Long referenceCount,
                                  List<Object> references, boolean canRetire,
                                  String reasonCode) {}
}
