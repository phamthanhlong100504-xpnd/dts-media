package com.dts.media.api.request;

import lombok.Data;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Data
public class UpdateMediaRequest {
    @Pattern(regexp = "PUBLIC|PRIVATE") private String visibility;
    @Size(min = 1, max = 500) private String originalFilename;
}
