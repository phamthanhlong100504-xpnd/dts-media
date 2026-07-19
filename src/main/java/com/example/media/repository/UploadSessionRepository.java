package com.example.media.repository;

import com.example.media.domain.UploadSession;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface UploadSessionRepository extends JpaRepository<UploadSession, UUID> {
    // Additional query methods if needed
}
