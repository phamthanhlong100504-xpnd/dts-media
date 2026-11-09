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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminMediaServiceTest {
    private final MediaRepository repo = mock(MediaRepository.class);
    private final MediaService existing = mock(MediaService.class);
    private final AdminMediaService service = new AdminMediaService(repo, existing);
    private final UUID actorId = UUID.randomUUID();
    private final UUID mediaId = UUID.randomUUID();

    private void login(String role, String... permissions) {
        MediaActor actor = new MediaActor(actorId, Set.of(role), Set.of(permissions));
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(actor, "n/a", List.of()));
    }

    private Media media(MediaStatus status) {
        return Media.builder().id(mediaId).mediaType("VIDEO").uploadTargetType("SIMULATION_VIDEO")
            .originalFilename("scenario.mp4").mimeType("video/mp4").sizeBytes(100L)
            .status(status).visibility("PRIVATE").createdBy(actorId).createdAt(OffsetDateTime.now()).build();
    }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void noLoginCannotSeeAdminMedia() {
        assertThrows(ResponseStatusException.class, () -> service.detail(mediaId));
        verifyNoInteractions(repo, existing);
    }
    @Test void learnerWithReadCannotSeeAdminMedia() {
        login("ROLE_USER", "media:read", "media:manage");
        assertThrows(SecurityException.class, () -> service.detail(mediaId));
        verifyNoInteractions(repo, existing);
    }
    @Test void adminWithoutManageCannotSeeAdminMedia() {
        login("ROLE_ADMIN", "media:read");
        assertThrows(SecurityException.class, () -> service.detail(mediaId));
        verifyNoInteractions(repo, existing);
    }
    @Test void adminCanInspectFailedWithoutSignedUrl() {
        login("ROLE_ADMIN", "media:read", "media:manage");
        when(existing.findActive(mediaId)).thenReturn(media(MediaStatus.FAILED));
        var detail=service.detail(mediaId);
        assertEquals("FAILED", detail.status());
        assertFalse(detail.canRetire());
        assertNull(detail.referenceCount());
        assertEquals("REFERENCES_NOT_VERIFIED", detail.reasonCode());
        verify(existing, never()).getMediaDetail(any());
    }
    @Test void failedMediaCannotBePreviewed() {
        login("ROLE_ADMIN", "media:read", "media:manage");
        when(existing.findActive(mediaId)).thenReturn(media(MediaStatus.FAILED));
        assertEquals(409, assertThrows(ResponseStatusException.class,
            () -> service.preview(mediaId)).getStatusCode().value());
        verify(existing, never()).getMediaDetail(any());
    }
    @Test void noMediaCanBeRetiredUntilReferencesAreVerified() {
        login("ROLE_ADMIN", "media:read", "media:manage");
        when(existing.findActive(mediaId)).thenReturn(media(MediaStatus.READY));
        assertEquals(409, assertThrows(ResponseStatusException.class,
            () -> service.retire(mediaId)).getStatusCode().value());
        var refs = service.references(mediaId);
        assertFalse(refs.verified());
        assertFalse(refs.canRetire());
        assertNull(refs.referenceCount());
        verify(repo, never()).save(any());
    }
}
