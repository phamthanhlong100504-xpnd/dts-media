package com.dts.media.api.response;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SimulationCatalogThumbnailResponse(UUID mediaId, UUID scenarioId, String bankVersion,
                                                 String url, OffsetDateTime expiredAt) {}
