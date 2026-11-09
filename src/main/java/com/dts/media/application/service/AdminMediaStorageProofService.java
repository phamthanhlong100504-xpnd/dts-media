package com.dts.media.application.service;

import com.dts.media.application.enums.MediaStatus;
import com.dts.media.domain.entity.Media;
import com.dts.media.domain.entity.Storage;
import com.dts.media.domain.repository.MediaRepository;
import com.dts.media.domain.repository.StorageRepository;
import com.dts.media.security.MediaAccess;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * BE02 Stage 5B2B: on-demand read-only streaming proof of the object currently in MinIO.
 * The expected hash is taken from server-owned Media metadata, NEVER from a browser.
 * A successful result proves only this object at this instant, not approved-content
 * binding, manifest correctness, downstream readiness or authority to publish/retire.
 *
 * SHA-256 is computed over every byte, not the MinIO ETag (which may be multipart).
 */
@Service
public class AdminMediaStorageProofService {
    private static final long MAX_VIDEO_BYTES = 2L * 1024 * 1024 * 1024;
    private static final long MAX_THUMB_BYTES = 20L * 1024 * 1024;
    private static final int BUFFER_BYTES = 64 * 1024;
    private final Semaphore singleReader = new Semaphore(1);
    private final MediaRepository mediaRepository;
    private final StorageRepository storageRepository;
    private final MediaStorageGuard guard;
    private final MinioClient minio;

    public AdminMediaStorageProofService(MediaRepository mediaRepository,
                                        StorageRepository storageRepository,
                                        MediaStorageGuard guard,
                                        @Qualifier("internalMinioClient") MinioClient minio) {
        this.mediaRepository = mediaRepository;
        this.storageRepository = storageRepository;
        this.guard = guard;
        this.minio = minio;
    }

    public record StorageProofReport(UUID mediaId, boolean storageObjectVerified,
                                     boolean sha256Matches, boolean approvedContentBound,
                                     boolean canPublish, String reasonCode,
                                     Long verifiedSizeBytes, Instant verifiedAt,
                                     String verifiedSha256, String verifiedTargetType) {}

    private static void requireManager() {
        var actor = MediaAccess.require("media:manage");
        if (!actor.manager() || !actor.permissions().contains("media:read"))
            throw new SecurityException("Admin media read and manage permissions required");
    }

    private static StorageProofReport deny(UUID mediaId, String reason) {
        return new StorageProofReport(mediaId, false, false, false, false, reason, null, null, null, null);
    }

    private static boolean validSha(String sha) {
        return sha != null && sha.matches("(?i)[0-9a-f]{64}");
    }

    private static boolean knownScope(Media m) {
        return "PRIVATE".equals(m.getVisibility()) &&
            (("VIDEO".equals(m.getMediaType()) && "SIMULATION_VIDEO".equals(m.getUploadTargetType())
                  && "video/mp4".equals(m.getMimeType())) ||
             ("IMAGE".equals(m.getMediaType()) && "SIMULATION_THUMBNAIL".equals(m.getUploadTargetType())
                  && "image/webp".equals(m.getMimeType())));
    }

    private static boolean hasExpectedHeader(byte[] first, int count, boolean video) {
        if (count < 12) return false;
        if (video) return first[4] == 'f' && first[5] == 't' && first[6] == 'y' && first[7] == 'p';
        return first[0] == 'R' && first[1] == 'I' && first[2] == 'F' && first[3] == 'F'
            && first[8] == 'W' && first[9] == 'E' && first[10] == 'B' && first[11] == 'P';
    }

    private ResponseStatusException storageUnavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_STORAGE_UNAVAILABLE");
    }

    @Transactional(readOnly = true)
    public StorageProofReport inspect(UUID mediaId) {
        requireManager(); // Must happen before any database or network request.
        if (mediaId == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MEDIA_ID_REQUIRED");
        Media media = mediaRepository.findById(mediaId).orElse(null);
        if (media == null || media.getDeletedAt() != null) return deny(mediaId, "MEDIA_MISSING_OR_DELETED");
        if (media.getStatus() != MediaStatus.READY) return deny(mediaId, "MEDIA_NOT_READY");
        if (!knownScope(media)) return deny(mediaId, "MEDIA_SCOPE_MISMATCH");
        if (!validSha(media.getChecksum())) return deny(mediaId, "MEDIA_SHA256_NOT_RECORDED");
        if (media.getSizeBytes() == null || media.getSizeBytes() <= 0
                || media.getObjectKey() == null || media.getObjectKey().isBlank()
                || media.getStorageId() == null) return deny(mediaId, "MEDIA_METADATA_INCOMPLETE");

        boolean video = "VIDEO".equals(media.getMediaType());
        long maxSize = video ? MAX_VIDEO_BYTES : MAX_THUMB_BYTES;
        if (media.getSizeBytes() > maxSize || media.getSizeBytes() < 12)
            return deny(mediaId, "MEDIA_SIZE_OUT_OF_POLICY");

        Storage storage = storageRepository.findById(media.getStorageId()).orElse(null);
        if (storage == null || storage.getDeletedAt() != null || !"ACTIVE".equals(storage.getStatus())
                || !"MINIO".equals(storage.getProvider()) || storage.getBucket() == null
                || storage.getBucket().isBlank()) return deny(mediaId, "STORAGE_NOT_READY");

        // A full SHA of a 2-GiB asset is costly. Bound this diagnostic to one active
        // reader per service instance; never queue unlimited expensive requests.
        if (!singleReader.tryAcquire())
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "STORAGE_PROOF_BUSY");
        try {
            guard.requirePrivate(storage);
            var stat = minio.statObject(StatObjectArgs.builder()
                .bucket(storage.getBucket()).object(media.getObjectKey()).build());
            if (stat.size() != media.getSizeBytes() || stat.size() > maxSize || stat.size() < 12
                    || !media.getMimeType().equals(stat.contentType()))
                return deny(mediaId, "OBJECT_METADATA_MISMATCH");
            String etag = stat.etag();
            if (etag == null || !etag.matches("[A-Za-z0-9_-]{1,128}"))
                throw storageUnavailable();

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            byte[] first = new byte[12];
            int headerCount = 0;
            try (InputStream stream = minio.getObject(GetObjectArgs.builder()
                    .bucket(storage.getBucket()).object(media.getObjectKey())
                    .matchETag(etag).build())) {
                byte[] buffer = new byte[BUFFER_BYTES];
                int n;
                while ((n = stream.read(buffer)) != -1) {
                    if (n == 0) continue;
                    total += n;
                    if (total > stat.size()) return deny(mediaId, "OBJECT_SIZE_CHANGED");
                    int take = Math.min(n, first.length - headerCount);
                    if (take > 0) {
                        System.arraycopy(buffer, 0, first, headerCount, take);
                        headerCount += take;
                    }
                    digest.update(buffer, 0, n);
                }
            }
            if (total != stat.size()) return deny(mediaId, "OBJECT_SIZE_CHANGED");
            if (!hasExpectedHeader(first, headerCount, video))
                return deny(mediaId, "OBJECT_FORMAT_INVALID");
            String observedSha256 = HexFormat.of().formatHex(digest.digest());
            if (!observedSha256.equalsIgnoreCase(media.getChecksum()))
                return deny(mediaId, "OBJECT_SHA256_MISMATCH");
            // Never save a certificate to DB or authorize a release here: the source
            // manifest/approved revision has not yet been cryptographically bound.
            return new StorageProofReport(mediaId, true, true, false, false,
                    "OBJECT_BYTES_MATCH_MEDIA_RECORD_ONLY", total, Instant.now(),
                    observedSha256, media.getUploadTargetType());
        } catch (ErrorResponseException e) {
            if (e.errorResponse() != null && Set.of("NoSuchKey", "NoSuchObject", "NoSuchBucket")
                    .contains(e.errorResponse().code())) return deny(mediaId, "OBJECT_MISSING");
            if (e.errorResponse() != null && "PreconditionFailed".equals(e.errorResponse().code()))
                return deny(mediaId, "OBJECT_CHANGED");
            throw storageUnavailable();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            // Never leak object key, bucket, storage credentials or MinIO error strings.
            throw storageUnavailable();
        } finally {
            singleReader.release();
        }
    }
}
