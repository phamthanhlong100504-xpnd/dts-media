package com.dts.media.application.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.UUID;

@Data
@AllArgsConstructor
public class UploadVerificationEvent {
    private UUID sessionId;
    private UUID mediaId;
}
