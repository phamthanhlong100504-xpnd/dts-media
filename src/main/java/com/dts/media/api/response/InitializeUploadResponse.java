package com.dts.media.api.response;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class InitializeUploadResponse {

    private final String sessionId;
    private final String mediaId;
    private final String presignedUrl;
    private final String objectKey;
    private final String expiredAt; // ISO-8601 string
}
