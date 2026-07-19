package com.example.media.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

@Data
public class InitializeUploadRequest {
    @NotBlank
    private String fileName;

    @NotNull
    @Positive
    private Long sizeBytes;

    @NotBlank
    private String mimeType;

    @NotBlank
    private String visibility; // PUBLIC or PRIVATE

    @NotBlank
    private String targetType; // e.g., USER, ROLE
}
