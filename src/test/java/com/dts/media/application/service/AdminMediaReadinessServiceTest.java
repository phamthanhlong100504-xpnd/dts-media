package com.dts.media.application.service;

import com.dts.media.application.enums.MediaStatus;
import com.dts.media.domain.entity.Media;
import com.dts.media.domain.repository.MediaRepository;
import com.dts.media.security.MediaActor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AdminMediaReadinessServiceTest {
    private final MediaRepository repository = mock(MediaRepository.class);
    private final AdminMediaReadinessService service = new AdminMediaReadinessService(repository);
    private final UUID id = UUID.randomUUID();
    private static final String SHA = "a".repeat(64);

    private void login(String role, String... grants) {
        var actor = new MediaActor(UUID.randomUUID(), Set.of(role), Set.of(grants));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor, "n/a", List.of()));
    }
    private AdminMediaReadinessService.ExpectedMedia expected() {
        return new AdminMediaReadinessService.ExpectedMedia(id, "VIDEO", "SIMULATION_VIDEO",
                "sim.mp4", "video/mp4", 2048L, SHA);
    }
    private AdminMediaReadinessService.ReadinessRequest request() {
        return new AdminMediaReadinessService.ReadinessRequest(List.of(expected()));
    }
    private Media stored(MediaStatus state) {
        return Media.builder().id(id).status(state).deletedAt(null).visibility("PRIVATE")
                .mediaType("VIDEO").uploadTargetType("SIMULATION_VIDEO")
                .originalFilename("sim.mp4").mimeType("video/mp4")
                .sizeBytes(2048L).checksum(SHA).build();
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void authenticationRequiredBeforeDatabaseReads() {
        assertEquals(401, assertThrows(ResponseStatusException.class,
                () -> service.inspect(request())).getStatusCode().value());
        verifyNoInteractions(repository);
    }
    @Test void learnerCannotUseDiagnosticEvenWithPermission() {
        login("ROLE_USER", "media:read", "media:manage");
        assertThrows(SecurityException.class, () -> service.inspect(request()));
        verifyNoInteractions(repository);
    }
    @Test void adminMustHaveReadGrant() {
        login("ROLE_ADMIN", "media:manage");
        assertThrows(SecurityException.class, () -> service.inspect(request()));
        verifyNoInteractions(repository);
    }
    @Test void rejectsDuplicateOrInvalidAssetExpectationsBeforeQuery() {
        login("ROLE_ADMIN", "media:read", "media:manage");
        assertEquals(400, assertThrows(ResponseStatusException.class,
                () -> service.inspect(new AdminMediaReadinessService.ReadinessRequest(
                        List.of(expected(),expected())))).getStatusCode().value());
        verifyNoInteractions(repository);
    }
    @Test void readyAndMatchingRowsRemainUnpublishableWithoutStorageProof() {
        login("ROLE_ADMIN", "media:read", "media:manage");
        when(repository.findAllById(any())).thenReturn(List.of(stored(MediaStatus.READY)));
        var report = service.inspect(request());
        assertEquals(1, report.metadataMatched());
        assertTrue(report.metadataComplete());
        assertFalse(report.storageObjectVerified());
        assertFalse(report.approvedContentBound());
        assertFalse(report.canPublish());
        assertEquals("METADATA_MATCHED_STORAGE_UNVERIFIED",report.results().get(0).reasonCode());
        verify(repository,never()).save(any());
    }
    @Test void nonReadyMediaBlocksMetadataReadiness() {
        login("ROLE_ADMIN", "media:read", "media:manage");
        when(repository.findAllById(any())).thenReturn(List.of(stored(MediaStatus.FAILED)));
        var report = service.inspect(request());
        assertEquals("MEDIA_NOT_READY", report.results().get(0).reasonCode());
        assertFalse(report.metadataComplete());
        assertFalse(report.canPublish());
    }
    @Test void missingMediaCannotBeVerified() {
        login("ROLE_ADMIN", "media:read", "media:manage");
        when(repository.findAllById(any())).thenReturn(List.of());
        var report = service.inspect(request());
        assertEquals("MEDIA_MISSING_OR_DELETED", report.results().get(0).reasonCode());
        assertFalse(report.canPublish());
    }
    @Test void shaMismatchFailsClosed() {
        login("ROLE_ADMIN", "media:read", "media:manage");
        Media m = stored(MediaStatus.READY);m.setChecksum("b".repeat(64));
        when(repository.findAllById(any())).thenReturn(List.of(m));
        assertEquals("MEDIA_SHA256_MISMATCH",service.inspect(request()).results().get(0).reasonCode());
    }
    @Test void missingShaCannotBeTreatedAsVerified() {
        login("ROLE_ADMIN", "media:read", "media:manage");
        Media m = stored(MediaStatus.READY);m.setChecksum(null);
        when(repository.findAllById(any())).thenReturn(List.of(m));
        assertEquals("MEDIA_SHA256_NOT_RECORDED",service.inspect(request()).results().get(0).reasonCode());
    }
    @Test void targetMismatchFailsClosed() {
        login("ROLE_ADMIN", "media:read", "media:manage");
        Media m = stored(MediaStatus.READY);m.setUploadTargetType("OTHER");
        when(repository.findAllById(any())).thenReturn(List.of(m));
        assertEquals("MEDIA_SCOPE_MISMATCH",service.inspect(request()).results().get(0).reasonCode());
    }
}
