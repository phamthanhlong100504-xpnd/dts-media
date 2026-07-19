package com.example.media.api.dto;

import lombok.Data;

@Data
public class ConfirmUploadRequest {
    // checksum optional; not required from client per decision
    private String checksum;
}
