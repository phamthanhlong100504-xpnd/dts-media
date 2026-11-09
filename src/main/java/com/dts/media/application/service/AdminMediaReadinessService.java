package com.dts.media.application.service;

import com.dts.media.application.enums.MediaStatus;
import com.dts.media.domain.entity.Media;
import com.dts.media.domain.repository.MediaRepository;
import com.dts.media.security.MediaAccess;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Stage 5B2A. Diagnostic metadata checks, NOT a verification of bytes in object storage,
 * a content-review approval, or a release/retirement authorization. No signed URLs.
 * Inputs are untrusted, including if supplied by a browser. This result MUST NOT be used
 * to promote a release or to retire an asset. Later flows must derive expected hashes
 * on the server from an immutable approved candidate.
 */
@Service
public class AdminMediaReadinessService {
    private static final int MAX_ASSETS = 300;
    private final MediaRepository repository;

    public AdminMediaReadinessService(MediaRepository repository) {
        this.repository = repository;
    }

    public record ExpectedMedia(UUID mediaId, String mediaType, String targetType,
                                String filename, String mimeType, Long sizeBytes,
                                String sha256) {}
    public record ReadinessRequest(List<ExpectedMedia> media) {}
    public record AssetResult(UUID mediaId, boolean metadataMatches, String reasonCode) {}
    public record ReadinessReport(int examined, int metadataMatched, boolean metadataComplete,
                                  boolean storageObjectVerified, boolean approvedContentBound,
                                  boolean canPublish, List<AssetResult> results) {}

    private static void requireManager() {
        var actor = MediaAccess.require("media:read");
        if (!actor.manager()) throw new SecurityException("Admin media permission required");
    }

    private static void checkRequest(ReadinessRequest request) {
        if (request == null || request.media() == null || request.media().isEmpty()
                || request.media().size() > MAX_ASSETS) invalid();
        Set<UUID> ids = new HashSet<>();
        for (ExpectedMedia e : request.media()) {
            if (e == null || e.mediaId() == null || !ids.add(e.mediaId())
                    || e.filename() == null || e.filename().isBlank() || e.filename().length() > 255
                    || e.sizeBytes() == null || e.sizeBytes() <= 0
                    || e.sha256() == null || !e.sha256().matches("(?i)[0-9a-f]{64}")) invalid();
            boolean video = "VIDEO".equals(e.mediaType()) && "SIMULATION_VIDEO".equals(e.targetType())
                    && "video/mp4".equals(e.mimeType());
            boolean thumb = "IMAGE".equals(e.mediaType()) && "SIMULATION_THUMBNAIL".equals(e.targetType())
                    && "image/webp".equals(e.mimeType());
            if (!video && !thumb) invalid();
        }
    }

    private static void invalid() {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_MEDIA_EXPECTATIONS");
    }

    private static String status(Media m, ExpectedMedia e) {
        if (m == null || m.getDeletedAt() != null) return "MEDIA_MISSING_OR_DELETED";
        if (m.getStatus() != MediaStatus.READY) return "MEDIA_NOT_READY";
        if (!"PRIVATE".equals(m.getVisibility())
                || !e.mediaType().equals(m.getMediaType())
                || !e.targetType().equals(m.getUploadTargetType())) return "MEDIA_SCOPE_MISMATCH";
        if (!e.filename().equals(m.getOriginalFilename())
                || !e.mimeType().equals(m.getMimeType())
                || !e.sizeBytes().equals(m.getSizeBytes())) return "MEDIA_METADATA_MISMATCH";
        if (m.getChecksum() == null || !m.getChecksum().matches("(?i)[0-9a-f]{64}"))
            return "MEDIA_SHA256_NOT_RECORDED";
        if (!e.sha256().equalsIgnoreCase(m.getChecksum())) return "MEDIA_SHA256_MISMATCH";
        return "METADATA_MATCHED_STORAGE_UNVERIFIED";
    }

    @Transactional(readOnly = true)
    public ReadinessReport inspect(ReadinessRequest request) {
        // Check authority before inspecting either request IDs or database rows.
        requireManager();
        checkRequest(request);
        List<UUID> ids = request.media().stream().map(ExpectedMedia::mediaId).toList();
        Map<UUID, Media> known = new HashMap<>();
        for (Media row : repository.findAllById(ids)) {
            if (row != null && row.getId() != null) known.put(row.getId(), row);
        }
        List<AssetResult> results = new ArrayList<>(ids.size());
        int matched = 0;
        for (ExpectedMedia expected : request.media()) {
            String reason = status(known.get(expected.mediaId()), expected);
            boolean ok = "METADATA_MATCHED_STORAGE_UNVERIFIED".equals(reason);
            if (ok) matched++;
            results.add(new AssetResult(expected.mediaId(), ok, reason));
        }
        // Even all-READY/all-hash-matching Media rows do not establish object availability,
        // signed URL protection, approved content bytes, or consumer readiness.
        return new ReadinessReport(results.size(), matched, matched == results.size(),
                false, false, false, List.copyOf(results));
    }
}
