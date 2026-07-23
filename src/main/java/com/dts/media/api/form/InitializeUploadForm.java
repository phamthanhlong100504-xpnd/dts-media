package com.dts.media.api.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class InitializeUploadForm {

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
