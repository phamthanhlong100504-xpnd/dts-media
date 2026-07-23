package com.dts.media.api.response;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ConfirmUploadResponse {

    private final String sessionId;
    private final String mediaId;
}
