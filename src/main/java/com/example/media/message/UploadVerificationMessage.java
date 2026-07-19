package com.example.media.message;

import java.util.UUID;

public record UploadVerificationMessage(UUID sessionId, UUID mediaId) {}
