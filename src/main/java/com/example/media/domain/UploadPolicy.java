package com.example.media.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.GenericGenerator;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity
@Table(name = "upload_policies")
public class UploadPolicy {
    @Id
    @GeneratedValue(generator = "UUID")
    @GenericGenerator(name = "UUID", strategy = "org.hibernate.id.UUIDGenerator")
    @Column(name = "id", updatable = false, nullable = false, columnDefinition = "UUID")
    private UUID id;

    @Column(name = "target_type", nullable = false)
    private String targetType;

    @Column(name = "max_file_size", nullable = false)
    private Long maxFileSize;

    // Stored as comma‑separated MIME types for simplicity
    @Column(name = "allowed_mime_types", columnDefinition = "TEXT")
    private String allowedMimeTypes;

    @Column(name = "visibility", nullable = false)
    private String visibility;

    @Column(name = "created_at", nullable = false, columnDefinition = "TIMESTAMPTZ")
    private OffsetDateTime createdAt;
}
