package com.example.media.repository;

import com.example.media.domain.Media;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface MediaRepository extends JpaRepository<Media, UUID> {
    // Additional query methods if needed
}
