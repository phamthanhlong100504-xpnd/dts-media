package com.dts.media.domain.repository;

import com.dts.media.domain.entity.UploadSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;

import java.util.UUID;

public interface UploadSessionRepository extends JpaRepository<UploadSession, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM UploadSession s WHERE s.id = :id")
    Optional<UploadSession> findByIdForUpdate(@Param("id") UUID id);
}
