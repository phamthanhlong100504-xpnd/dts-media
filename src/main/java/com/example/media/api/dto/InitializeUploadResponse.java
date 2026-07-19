package com.example.media.api.dto;

import lombok.Data;

@Data
public class InitializeUploadResponse {
    private String sessionId;
    private String mediaId;
    private String presignedUrl;
    private String objectKey;
    private String expiredAt; // ISO-8601 string
}
