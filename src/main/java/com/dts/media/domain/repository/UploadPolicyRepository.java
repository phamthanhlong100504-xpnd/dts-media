package com.dts.media.domain.repository;

import com.dts.media.domain.entity.UploadPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UploadPolicyRepository extends JpaRepository<UploadPolicy, UUID> {
    Optional<UploadPolicy> findByTargetType(String targetType);
}
