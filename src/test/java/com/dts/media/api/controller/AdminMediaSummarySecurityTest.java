package com.dts.media.api.controller;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import static org.junit.jupiter.api.Assertions.*;

class AdminMediaSummarySecurityTest {
    @Test void summaryDoesNotExposeMediaWithoutReportGrant() throws Exception {
        var method=AdminMediaSummaryController.class.getMethod("summary");
        var policy=method.getAnnotation(PreAuthorize.class);
        assertNotNull(policy);assertTrue(policy.value().contains("ROLE_ADMIN"));
        assertTrue(policy.value().contains("PERM_reports:read"));
    }
}
