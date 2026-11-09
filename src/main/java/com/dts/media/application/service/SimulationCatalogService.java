package com.dts.media.application.service;

import com.dts.media.api.response.SimulationCatalogThumbnailResponse;
import com.dts.media.application.enums.MediaStatus;
import com.dts.media.security.MediaAccess;
import com.dts.media.security.SimulationCatalogTicketVerifier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class SimulationCatalogService {
    private final SimulationCatalogTicketVerifier tickets;
    private final MediaService mediaService;

    @Transactional(readOnly = true)
    public SimulationCatalogThumbnailResponse resolve(String ticket) {
        var actor = MediaAccess.require("media:read");
        MediaAccess.require("simulations:read");
        var scope = tickets.verify(ticket, actor.userId());
        var media = mediaService.findActive(scope.mediaId());
        if (media.getStatus() != MediaStatus.READY)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Simulation thumbnail not ready");
        if (!"PRIVATE".equals(media.getVisibility()) || !"IMAGE".equals(media.getMediaType())
                || (media.getUploadTargetType() != null && !"SIMULATION_THUMBNAIL".equals(media.getUploadTargetType())))
            throw new SecurityException("Media is outside the simulation thumbnail scope");
        // Legacy NULL targets require Practice's signed, published-bank binding too.
        UploadChecks.simulationFormat("SIMULATION_THUMBNAIL", media.getOriginalFilename(), media.getMimeType());
        var issued = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
        String url = mediaService.sign(media, 300); // Existing storage policy guard also applies here.
        return new SimulationCatalogThumbnailResponse(scope.mediaId(), scope.scenarioId(), scope.bankVersion(), url, issued.plusSeconds(300));
    }
}
