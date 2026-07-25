package com.dts.media.domain.repository;

import com.dts.media.domain.entity.UploadSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UploadSessionRepository extends JpaRepository<UploadSession, UUID> {
}
