package com.dts.media.api.request;

import lombok.Data;

@Data
public class UpdateMediaRequest {
    private String visibility;
    private String originalFilename;
}
