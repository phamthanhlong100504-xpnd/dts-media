package com.dts.media.api.controller;

import com.dts.media.api.request.UpdateMediaRequest;
import com.dts.media.api.response.MediaResponse;
import com.dts.media.application.enums.MediaStatus;
import com.dts.media.application.service.MediaService;
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
        Pageable pageable = PageRequest.of(page, size);
        Page<MediaResponse> response = mediaService.getMediaList(userId, mediaType, status, visibility, pageable);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{mediaId}")
    public ResponseEntity<MediaResponse> updateMedia(
            @PathVariable UUID mediaId,
            @RequestParam(required = false) UUID userId,
            @RequestBody UpdateMediaRequest request) {
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
