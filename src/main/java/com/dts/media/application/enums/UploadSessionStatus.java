package com.dts.media.application.enums;

public enum UploadSessionStatus {
    // Keep every state allowed by ck_upload_sessions_status (migration V1).
    PENDING, UPLOADING, COMPLETED, FAILED, CANCELLED, EXPIRED
}
