package com.dts.media.api.controller;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** BE06-09: owner-scoped, no-store operational evidence. No cross-service success is inferred. */
@RestController
public class AdminOperationalStatusController {
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    public AdminOperationalStatusController(org.springframework.jdbc.core.JdbcTemplate jdbc) { this.jdbc=jdbc; }

    @GetMapping("/api/v1/media/admin/operations/status")
    @PreAuthorize("hasAuthority('ROLE_ADMIN') and hasAuthority('PERM_system-status:read')")
    public ResponseEntity<Map<String,Object>> status() {

        Long notReady=jdbc.queryForObject("SELECT COUNT(*) FROM medias WHERE deleted_at IS NULL AND status <> 'READY'",Long.class);
        var out=new LinkedHashMap<String,Object>();out.put("sourceService","dts-media");out.put("asOf",Instant.now());
        out.put("dataStatus","PARTIAL");out.put("databaseRead","READY");out.put("notReadyMetadataCount",notReady);
        out.put("minioPrivateBucketVerified",null);out.put("minioObjectAvailabilityVerified",null);
        out.put("missingSources",List.of("minio_live_application_probe"));
        out.put("warnings",List.of("READY metadata does not prove the object is available in private MinIO storage"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(out);
    }
}
