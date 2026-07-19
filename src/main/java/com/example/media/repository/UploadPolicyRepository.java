package com.example.media.repository;

import com.example.media.domain.UploadPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface UploadPolicyRepository extends JpaRepository<UploadPolicy, UUID> {
    Optional<UploadPolicy> findByTargetType(String targetType);
}
