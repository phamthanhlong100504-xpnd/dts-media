package com.dts.media.api.controller;

import com.dts.media.api.response.MediaResponse;
import com.dts.media.application.enums.MediaStatus;
import com.dts.media.application.service.AdminMediaService;
import com.dts.media.application.service.AdminMediaReadinessService;
import com.dts.media.application.service.AdminMediaStorageProofService;
import com.dts.media.application.service.AdminMediaService.AdminMediaItem;
import com.dts.media.application.service.AdminMediaService.MediaReferences;
import org.springframework.data.domain.Page;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Admin-only metadata API. Existing student playback and public Media APIs are untouched. */
@RestController
@RequestMapping("/api/v1/media/admin")
public class AdminMediaController {
    private final AdminMediaService service;
    private final AdminMediaReadinessService readiness;
    private final AdminMediaStorageProofService storageProof;
    public AdminMediaController(AdminMediaService service, AdminMediaReadinessService readiness,
                                AdminMediaStorageProofService storageProof) {
        this.service = service;
        this.readiness = readiness;
        this.storageProof = storageProof;
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @GetMapping
    public ResponseEntity<Page<AdminMediaItem>> list(@RequestParam(required = false) UUID createdBy,
            @RequestParam(required = false) String mediaType,
            @RequestParam(required = false) MediaStatus status,
            @RequestParam(required = false) String visibility,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ok(service.list(createdBy, mediaType, status, visibility, page, size));
    }

    @GetMapping("/{mediaId}")
    public ResponseEntity<AdminMediaItem> detail(@PathVariable UUID mediaId) {
        return ok(service.detail(mediaId));
    }

    @GetMapping("/{mediaId}/references")
    public ResponseEntity<MediaReferences> references(@PathVariable UUID mediaId) {
        return ok(service.references(mediaId));
    }

    @GetMapping("/{mediaId}/preview")
    public ResponseEntity<MediaResponse> preview(@PathVariable UUID mediaId) {
        return ok(service.preview(mediaId));
    }

    /** Diagnostic only. It is NOT a release certificate or trusted approval-to-source binding. */
    @PostMapping("/readiness-check")
    public ResponseEntity<AdminMediaReadinessService.ReadinessReport> readinessCheck(
            @RequestBody AdminMediaReadinessService.ReadinessRequest request) {
        return ok(readiness.inspect(request));
    }

    /** Streaming check of one stored object. This is NOT an approval or release certificate. */
    @PostMapping("/{mediaId}/storage-proof")
    public ResponseEntity<AdminMediaStorageProofService.StorageProofReport> storageProof(
            @PathVariable UUID mediaId) {
        return ok(storageProof.inspect(mediaId));
    }

    @PostMapping("/{mediaId}/retire")
    public ResponseEntity<Void> retire(@PathVariable UUID mediaId) {
        service.retire(mediaId);
        return ResponseEntity.noContent().build();
    }
}
