package com.dts.media.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import static org.junit.jupiter.api.Assertions.*;

class AdminOperationalStatusSecurityTest {
    @Test void ownerStatusDoesNotInventUnavailableDependenciesOrLeakyCaching() {
        var db=org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class);
        org.mockito.Mockito.when(db.queryForObject(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq(Long.class))).thenReturn(12L);
        var reply=new AdminOperationalStatusController(db).status();
        assertEquals("PARTIAL",reply.getBody().get("dataStatus"));
        assertEquals(12L,reply.getBody().get("notReadyMetadataCount"));
        assertNull(reply.getBody().get("minioPrivateBucketVerified"));
        assertNull(reply.getBody().get("minioObjectAvailabilityVerified"));
        assertEquals("no-store",reply.getHeaders().getFirst("Cache-Control"));
    }
    @Test void operationHasIndependentSystemStatusGrant() throws Exception {
        var method=AdminOperationalStatusController.class.getMethod("status");
        var policy=method.getAnnotation(PreAuthorize.class);
        assertNotNull(policy);
        assertTrue(policy.value().contains("ROLE_ADMIN"));
        assertTrue(policy.value().contains("PERM_system-status:read"));
        assertFalse(policy.value().contains("PERM_reports:read"));
        assertFalse(policy.value().contains("PERM_integrations:read"));
        assertEquals("/api/v1/media/admin/operations/status",method.getAnnotation(GetMapping.class).value()[0]);
        assertEquals(ResponseEntity.class,method.getReturnType());
    }
}
