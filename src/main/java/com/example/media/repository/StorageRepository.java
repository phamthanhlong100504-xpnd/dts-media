package com.example.media.repository;

import com.example.media.domain.Storage;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface StorageRepository extends JpaRepository<Storage, UUID> {
    Optional<Storage> findByIsDefaultTrue();
}
