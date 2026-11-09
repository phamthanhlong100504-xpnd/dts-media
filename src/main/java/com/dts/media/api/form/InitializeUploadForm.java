package com.dts.media.api.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class InitializeUploadForm {

    @NotBlank
    @Size(max = 500)
    private String fileName;

    @NotNull
    @Positive
    private Long sizeBytes;

    @NotBlank
    @Size(max = 255)
    private String mimeType;

    @NotBlank
    @Pattern(regexp = "PUBLIC|PRIVATE")
    private String visibility; // PUBLIC or PRIVATE

    @NotBlank
    @Pattern(regexp = "USER|ROLE|GROUP|SERVICE|TENANT|QUESTION|EXAM|TRAFFIC_SIGN|SIMULATION_VIDEO|SIMULATION_THUMBNAIL")
    private String targetType; // e.g., USER, ROLE
}
