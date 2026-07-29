package com.dts.media.domain.repository;

import com.dts.media.domain.entity.Media;
import com.dts.media.application.enums.MediaStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface MediaRepository extends JpaRepository<Media, UUID> {

    @Query("SELECT m FROM Media m WHERE (cast(:userId as uuid) IS NULL OR m.createdBy = :userId) AND m.deletedAt IS NULL " +
           "AND (:mediaType IS NULL OR m.mediaType = :mediaType) " +
           "AND (:status IS NULL OR m.status = :status) " +
           "AND (:visibility IS NULL OR m.visibility = :visibility)")
    Page<Media> findMediasByFilters(
            @Param("userId") UUID userId,
            @Param("mediaType") String mediaType,
            @Param("status") MediaStatus status,
            @Param("visibility") String visibility,
            Pageable pageable);
}
