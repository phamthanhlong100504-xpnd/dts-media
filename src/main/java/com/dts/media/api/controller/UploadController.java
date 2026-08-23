package com.dts.media.api.controller;

import com.dts.media.api.form.InitializeUploadForm;
import com.dts.media.api.response.ConfirmUploadResponse;
import com.dts.media.api.response.InitializeUploadResponse;
import com.dts.media.application.service.UploadService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/media/uploads")
public class UploadController {

    private final UploadService uploadService;

    @PostMapping("/initialize")
    public ResponseEntity<InitializeUploadResponse> initializeUpload(
            @Valid @RequestBody InitializeUploadForm form,
            @RequestHeader("Authorization") String authHeader) {
        String uploaderId = extractUserIdFromToken(authHeader);
        InitializeUploadResponse response = uploadService.initializeUpload(form, uploaderId);
        return ResponseEntity.status(201).body(response);
    }

    @PostMapping("/{sessionId}/confirm")
    public ResponseEntity<ConfirmUploadResponse> confirmUpload(
            @PathVariable UUID sessionId,
            @RequestHeader("Authorization") String authHeader) {
        String uploaderId = extractUserIdFromToken(authHeader);
        ConfirmUploadResponse response = uploadService.confirmUpload(sessionId, uploaderId);
        return ResponseEntity.accepted().body(response);
    }

    private String extractUserIdFromToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing or invalid Authorization header");
        }
        try {
            String token = authHeader.substring(7);
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                throw new IllegalArgumentException("Invalid JWT format");
            }
            String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            
            Pattern pattern = Pattern.compile("\"sub\"\\s*:\\s*\"([^\"]+)\"");
            Matcher matcher = pattern.matcher(payload);
            if (matcher.find()) {
                return matcher.group(1);
            }
            throw new IllegalArgumentException("Subject (sub) not found in token");
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid JWT token: " + e.getMessage());
        }
    }
}