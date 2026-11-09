package com.dts.media.api.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class SimulationPlaybackForm {
    @NotBlank @Size(max = 8192) private String ticket;
}
