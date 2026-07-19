package com.example.media.api;

import com.example.media.api.dto.*;
import com.example.media.service.UploadService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/media/uploads")
public class UploadController {

    private final UploadService uploadService;

    @PostMapping("/initialize")
    public ResponseEntity<InitializeUploadResponse> initializeUpload(
            @Valid @RequestBody InitializeUploadRequest request,
            @RequestHeader("X-User-Id") String uploaderId) {
        InitializeUploadResponse response = uploadService.initializeUpload(request, uploaderId);
        return ResponseEntity.status(201).body(response);
    }

    @PostMapping("/{sessionId}/confirm")
    public ResponseEntity<ConfirmUploadResponse> confirmUpload(
            @PathVariable UUID sessionId,
            @RequestHeader("X-User-Id") String uploaderId) {
        ConfirmUploadResponse response = uploadService.confirmUpload(sessionId, uploaderId);
        return ResponseEntity.accepted().body(response);
    }
}
