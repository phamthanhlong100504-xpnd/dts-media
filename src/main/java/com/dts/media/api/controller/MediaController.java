package com.dts.media.api.controller;

import com.dts.media.api.response.MediaResponse;
import com.dts.media.application.service.MediaService;
import lombok.RequiredArgsConstructor;
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
}
