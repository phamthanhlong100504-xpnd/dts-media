package com.dts.media.api.controller;

import com.dts.media.api.request.UpdateMediaRequest;
import com.dts.media.api.response.MediaResponse;
import com.dts.media.application.enums.MediaStatus;
import com.dts.media.application.service.MediaService;
import com.dts.media.application.service.SimulationPlaybackService;
import com.dts.media.api.form.SimulationPlaybackForm;
import com.dts.media.api.response.SimulationPlaybackResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.data.domain.Sort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/media")
public class MediaController {

    private final MediaService mediaService;
    private final SimulationPlaybackService simulationPlaybackService;

    @PostMapping("/simulation-playback")
    @PreAuthorize("hasAuthority('PERM_media:read') and hasAuthority('PERM_simulations:read')")
    public ResponseEntity<SimulationPlaybackResponse> simulationPlayback(@Valid @RequestBody SimulationPlaybackForm form) {
        return ResponseEntity.ok(simulationPlaybackService.resolve(form.getTicket()));
    }

    @GetMapping("/{mediaId}")
    public ResponseEntity<MediaResponse> getMediaDetail(@PathVariable UUID mediaId) {
        MediaResponse response = mediaService.getMediaDetail(mediaId);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<Page<MediaResponse>> getMediaList(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) String mediaType,
            @RequestParam(required = false) MediaStatus status,
            @RequestParam(required = false) String visibility,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Page/size out of range");
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        Page<MediaResponse> response = mediaService.getMediaList(userId, mediaType, status, visibility, pageable);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{mediaId}")
    public ResponseEntity<MediaResponse> updateMedia(
            @PathVariable UUID mediaId,
            @RequestParam(required = false) UUID userId,
            @Valid @RequestBody UpdateMediaRequest request) {
        MediaResponse response = mediaService.updateMedia(mediaId, userId, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{mediaId}")
    public ResponseEntity<Void> deleteMedia(
            @PathVariable UUID mediaId,
            @RequestParam(required = false) UUID userId) {
        mediaService.deleteMedia(mediaId, userId);
        return ResponseEntity.noContent().build();
    }
}
