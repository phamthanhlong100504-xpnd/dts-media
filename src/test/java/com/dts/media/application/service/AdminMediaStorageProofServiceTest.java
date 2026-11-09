package com.dts.media.application.service;

import com.dts.media.application.enums.MediaStatus;
import com.dts.media.domain.entity.Media;
import com.dts.media.domain.entity.Storage;
import com.dts.media.domain.repository.MediaRepository;
import com.dts.media.domain.repository.StorageRepository;
import com.dts.media.security.MediaActor;
import io.minio.*;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import okhttp3.Headers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AdminMediaStorageProofServiceTest {
    private final MediaRepository medias = mock(MediaRepository.class);
    private final StorageRepository storages = mock(StorageRepository.class);
    private final MediaStorageGuard guard = mock(MediaStorageGuard.class);
    private final MinioClient minio = mock(MinioClient.class);
    private final StatObjectResponse stat = mock(StatObjectResponse.class);
    private final AdminMediaStorageProofService service =
        new AdminMediaStorageProofService(medias, storages, guard, minio);
    private final UUID id = UUID.randomUUID();
    private final UUID storageId = UUID.randomUUID();
    private Media media;
    private Storage storage;
    private byte[] bytes;

    private void login(String role, String... permissions) {
        var actor = new MediaActor(UUID.randomUUID(), Set.of(role), Set.of(permissions));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor, "n/a", List.of()));
    }
    private void manager() { login("ROLE_ADMIN", "media:manage", "media:read"); }
    private static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }
    private static byte[] mp4() {
        byte[] data = new byte[64];
        data[0] = 0; data[1] = 0; data[2] = 0; data[3] = 24;
        data[4] = 'f'; data[5] = 't'; data[6] = 'y'; data[7] = 'p';
        data[8] = 'i'; data[9] = 's'; data[10] = 'o'; data[11] = 'm';
        return data;
    }
    private static byte[] webp() {
        byte[] data = new byte[64];
        System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII),0,data,0,4);
        System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII),0,data,8,4);
        return data;
    }
    private void payload(byte[] value) throws Exception {
        bytes = value;
        media.setSizeBytes((long) value.length);
        media.setChecksum(sha(value));
        when(stat.size()).thenReturn((long) value.length);
        doAnswer(i -> new GetObjectResponse(new Headers.Builder().build(),
             "media", null, "test.mp4", new ByteArrayInputStream(bytes)))
             .when(minio).getObject(any(GetObjectArgs.class));
    }
    @BeforeEach void setup() throws Exception {
        media = Media.builder().id(id).storageId(storageId)
            .objectKey("uploads/2026/11/sample.mp4")
            .status(MediaStatus.READY).deletedAt(null)
            .mediaType("VIDEO").uploadTargetType("SIMULATION_VIDEO")
            .visibility("PRIVATE").mimeType("video/mp4")
            .build();
        storage = Storage.builder().id(storageId).provider("MINIO")
            .status("ACTIVE").bucket("media").build();
        when(medias.findById(id)).thenReturn(Optional.of(media));
        when(storages.findById(storageId)).thenReturn(Optional.of(storage));
        when(minio.statObject(any(StatObjectArgs.class))).thenReturn(stat);
        when(stat.contentType()).thenReturn("video/mp4");
        when(stat.etag()).thenReturn("0123456789abcdef");
        payload(mp4());
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }

    @Test void anonymousIsDeniedBeforeAnyDatabaseOrMinioReads() throws Exception {
        assertEquals(401, assertThrows(ResponseStatusException.class,
                () -> service.inspect(id)).getStatusCode().value());
        verify(medias, never()).findById(any());
        verify(minio, never()).statObject(any());
    }
    @Test void learnerWithGrantsIsDeniedBeforeStorageReads() {
        login("ROLE_USER", "media:read", "media:manage");
        assertThrows(SecurityException.class, () -> service.inspect(id));
        verify(medias, never()).findById(any());
    }
    @Test void adminNeedsBothReadAndManageGrants() {
        login("ROLE_ADMIN", "media:manage");
        assertThrows(SecurityException.class, () -> service.inspect(id));
        verify(medias, never()).findById(any());
    }
    @Test void notReadyOrMissingShaNeverAccessesObjectStore() throws Exception {
        manager();
        media.setStatus(MediaStatus.FAILED);
        assertEquals("MEDIA_NOT_READY", service.inspect(id).reasonCode());
        media.setStatus(MediaStatus.READY);
        media.setChecksum(null);
        assertEquals("MEDIA_SHA256_NOT_RECORDED", service.inspect(id).reasonCode());
        verify(minio, never()).statObject(any());
        verify(minio, never()).getObject(any(GetObjectArgs.class));
    }
    @Test void verifiedVideoBytesNeverAuthorizePublishOrBindApproval() throws Exception {
        manager();
        var result = service.inspect(id);
        assertTrue(result.storageObjectVerified());
        assertTrue(result.sha256Matches());
        assertFalse(result.approvedContentBound());
        assertFalse(result.canPublish());
        assertEquals("OBJECT_BYTES_MATCH_MEDIA_RECORD_ONLY", result.reasonCode());
        assertEquals(64L, result.verifiedSizeBytes());
        assertNotNull(result.verifiedAt());
        assertEquals(media.getChecksum(), result.verifiedSha256());
        assertEquals("SIMULATION_VIDEO", result.verifiedTargetType());
        var getArgs = ArgumentCaptor.forClass(GetObjectArgs.class);
        verify(minio).getObject(getArgs.capture());
        assertEquals(stat.etag(), getArgs.getValue().matchETag());
        verify(guard).requirePrivate(storage);
        verify(medias, never()).save(any());
    }
    @Test void verifiesActualWebpHeaderAndBytes() throws Exception {
        manager();
        media.setMediaType("IMAGE");
        media.setUploadTargetType("SIMULATION_THUMBNAIL");
        media.setMimeType("image/webp");
        when(stat.contentType()).thenReturn("image/webp");
        payload(webp());
        var result = service.inspect(id);
        assertTrue(result.storageObjectVerified());
        assertFalse(result.canPublish());
    }
    @Test void falseHashFailsClosedWithoutPublishing() throws Exception {
        manager();
        media.setChecksum("a".repeat(64));
        var result = service.inspect(id);
        assertEquals("OBJECT_SHA256_MISMATCH", result.reasonCode());
        assertFalse(result.storageObjectVerified());
        assertFalse(result.canPublish());
    }
    @Test void badHeaderFailsEvenIfShaMatches() throws Exception {
        manager();
        payload("not a real mp4 content".getBytes(StandardCharsets.UTF_8));
        var result = service.inspect(id);
        assertEquals("OBJECT_FORMAT_INVALID", result.reasonCode());
        assertFalse(result.canPublish());
    }
    @Test void sizeOrMimeMismatchStopsBeforeAnyDownload() throws Exception {
        manager();
        when(stat.size()).thenReturn(1024L);
        assertEquals("OBJECT_METADATA_MISMATCH", service.inspect(id).reasonCode());
        verify(minio, never()).getObject(any(GetObjectArgs.class));
    }
    @Test void privateBucketPolicyIsCheckedBeforeStat() throws Exception {
        manager();
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
             "PRIVATE_POLICY_ERROR")).when(guard).requirePrivate(storage);
        assertEquals(503, assertThrows(ResponseStatusException.class,
                () -> service.inspect(id)).getStatusCode().value());
        verify(minio, never()).statObject(any());
    }
    @Test void missingObjectReportsUnverifiedWithoutExposingStorageLocation() throws Exception {
        manager();
        ErrorResponseException ex = mock(ErrorResponseException.class);
        ErrorResponse response = mock(ErrorResponse.class);
        when(response.code()).thenReturn("NoSuchKey");
        when(ex.errorResponse()).thenReturn(response);
        when(minio.statObject(any(StatObjectArgs.class))).thenThrow(ex);
        var result = service.inspect(id);
        assertEquals("OBJECT_MISSING", result.reasonCode());
        assertFalse(result.canPublish());
        assertFalse(result.toString().contains("uploads/"));
    }
    @Test void storageTransportErrorFailsWith503() throws Exception {
        manager();
        when(minio.statObject(any(StatObjectArgs.class)))
            .thenThrow(new IOException("network or credentials unavailable"));
        assertEquals(503, assertThrows(ResponseStatusException.class,
                () -> service.inspect(id)).getStatusCode().value());
    }
}
