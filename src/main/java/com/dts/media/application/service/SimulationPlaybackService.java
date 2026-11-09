package com.dts.media.application.service;

import com.dts.media.api.response.SimulationPlaybackResponse;
import com.dts.media.application.enums.MediaStatus;
import com.dts.media.security.MediaAccess;
import com.dts.media.security.MediaJwtVerifier;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SimulationPlaybackService {
    private final MediaJwtVerifier jwt;
    private final MediaService mediaService;

    @Transactional(readOnly = true)
    public SimulationPlaybackResponse resolve(String ticket) {
        var actor = MediaAccess.require("media:read"); MediaAccess.require("simulations:read");
        UUID mediaId; String bankVersion; String target;
        try {
            if (ticket == null || ticket.length() > 8192) throw new IllegalArgumentException();
            Claims c = jwt.playbackClaims(ticket);
            Instant now = Instant.now();
            if (c.getIssuedAt() == null || c.getExpiration() == null || c.getAudience() == null || !c.getAudience().equals(Set.of("dts-media"))
                    || c.getIssuedAt().toInstant().isAfter(now) || !c.getExpiration().toInstant().isAfter(now)
                    || c.getExpiration().getTime() - c.getIssuedAt().getTime() > 120000
                    || !actor.userId().equals(MediaJwtVerifier.canonicalUuid(c.getSubject()))) throw new IllegalArgumentException();
            mediaId = MediaJwtVerifier.canonicalUuid(c.get("mediaId", String.class));
            MediaJwtVerifier.canonicalUuid(c.get("attemptId", String.class));
            bankVersion = c.get("bankVersion", String.class); target = c.get("targetType", String.class);
            if (bankVersion == null || !bankVersion.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")
                    || !MediaAccess.simulation(target)) throw new IllegalArgumentException();
        } catch (Exception failure) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid or expired simulation playback ticket");
        }
        var media = mediaService.findActive(mediaId);
        if (media.getStatus() != MediaStatus.READY) throw new ResponseStatusException(HttpStatus.CONFLICT, "Simulation media not ready");
        if (!"PRIVATE".equals(media.getVisibility()) || (media.getUploadTargetType() != null && !target.equals(media.getUploadTargetType())))
            throw new SecurityException("Media is outside the simulation playback scope");
        // NULL target is permitted only for legacy files explicitly attested by Practice.
        UploadChecks.simulationFormat(target, media.getOriginalFilename(), media.getMimeType());
        var issued = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String url = mediaService.sign(media, 300);
        return new SimulationPlaybackResponse(mediaId, url, OffsetDateTime.ofInstant(issued.plusSeconds(300), ZoneOffset.UTC), bankVersion);
    }
}
