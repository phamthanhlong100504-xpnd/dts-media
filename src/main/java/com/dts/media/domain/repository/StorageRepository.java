package com.dts.media.domain.repository;

import com.dts.media.domain.entity.Storage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface StorageRepository extends JpaRepository<Storage, UUID> {
    Optional<Storage> findByIsDefaultTrue();
}
