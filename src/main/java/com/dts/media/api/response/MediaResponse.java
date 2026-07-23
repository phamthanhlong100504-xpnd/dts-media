package com.dts.media.api.response;

import lombok.Builder;
import lombok.Getter;

import java.time.OffsetDateTime;

@Getter
@Builder
public class MediaResponse {
    private final String mediaId;
    private final String originalFilename;
    private final String extension;
    private final String mimeType;
    private final Long sizeBytes;
    private final String mediaType;
    private final String visibility;
    private final String status;
    private final String url;
    private final OffsetDateTime createdAt;
}
