package com.dts.media.api.response;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SimulationPlaybackResponse(UUID mediaId, String url, OffsetDateTime expiredAt, String bankVersion) {}
