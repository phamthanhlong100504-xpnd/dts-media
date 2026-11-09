package com.dts.media.api.controller;

import com.dts.media.application.service.PublishedQuestionImageService;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Exchanges a short-lived, actor-bound Practice proof for a 60-second image URL. Read-only operation. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/media")
public class PublishedQuestionImageController {
    private final PublishedQuestionImageService images;

    public record ImageRequest(String mediaId, String ticket) {}

    @PostMapping("/published-question-image")
    @PreAuthorize("hasAuthority('PERM_media:read') and hasAuthority('PERM_practice:read')")
    public ResponseEntity<Map<String, String>> resolve(@RequestBody ImageRequest input) {
        if (input == null || input.ticket() == null || input.ticket().isBlank() || input.ticket().length() > 4096)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid image request");
        UUID id;
        try { id = UUID.fromString(input.mediaId());
            if (!id.toString().equalsIgnoreCase(input.mediaId())) throw new IllegalArgumentException();
        } catch (Exception ex) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid image request"); }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Map.of("url", images.resolve(id, input.ticket())));
    }
}
