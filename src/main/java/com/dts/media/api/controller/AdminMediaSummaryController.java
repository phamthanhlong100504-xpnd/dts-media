package com.dts.media.api.controller;

import com.dts.media.security.MediaAccess;
import java.time.Instant;
import java.util.*;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/media/admin/summary")
public class AdminMediaSummaryController {
    private final JdbcTemplate jdbc;
    public AdminMediaSummaryController(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @GetMapping
    @PreAuthorize("hasAuthority('ROLE_ADMIN') and hasAuthority('PERM_reports:read')")
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ResponseEntity<Map<String,Object>> summary(){
        // Both dedicated reporting and Media permission required; never return object keys or signed links.
        MediaAccess.require("media:manage");
        var groups=jdbc.queryForList("""
            SELECT media_type,status::text AS status,COUNT(*)::bigint total
              FROM medias WHERE deleted_at IS NULL
              GROUP BY media_type,status ORDER BY media_type,status
            """);
        Long notReady=jdbc.queryForObject("SELECT COUNT(*) FROM medias WHERE deleted_at IS NULL AND status<>'READY'",Long.class);
        var out=new LinkedHashMap<String,Object>();out.put("asOf",Instant.now());out.put("sourceService","dts-media");
        out.put("dataStatus","READY");out.put("byMediaTypeAndStatus",groups);out.put("notReadyCount",notReady);
        out.put("referencedCount",null);out.put("warnings",List.of("Cross-service reference coverage has not been verified"));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(out);
    }
}
