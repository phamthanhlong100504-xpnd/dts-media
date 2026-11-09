package com.dts.media.api.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Internal Practice-to-Media DTOs. Practice must not forward these identifiers to an active exam player. */
public final class SimulationExamMediaModel {
    private SimulationExamMediaModel() {}
    public record Asset(UUID mediaId,String fileName,String sha256,int durationMs,int videoTrackDurationMs) {}
    public record EligibilityScope(String bankVersion,String manifestSha256,List<Asset> candidates) {}
    public record PlaybackScope(String bankVersion,String manifestSha256,UUID sessionId,int position,
                                UUID playbackEventId,Asset asset,String objectEtag,boolean review) {}
    public record EligibilityItem(UUID mediaId,boolean ready,int durationMs,int videoTrackDurationMs,String objectEtag,String reason) {}
    public record EligibilityResponse(String bankVersion,String manifestSha256,List<EligibilityItem> items) {}
    public record PlaybackResponse(UUID sessionId,int position,UUID playbackEventId,String bankVersion,String manifestSha256,
                                   UUID mediaId,String url,OffsetDateTime expiredAt,String mimeType,int durationMs,String usage) {}
}
