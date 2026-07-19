package com.example.media.api.dto;

import lombok.Data;

@Data
public class ConfirmUploadResponse {
    private String status; // "UPLOADING"
    private String sessionId;
}
