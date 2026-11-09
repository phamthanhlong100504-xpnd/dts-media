package com.dts.media.application.service;

import com.dts.media.application.enums.MediaStatus;
import com.dts.media.security.MediaAccess;
import com.dts.media.security.MediaJwtVerifier;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class PublishedQuestionImageService {
    private final MediaService media;
    private final MediaJwtVerifier tickets;

    @Transactional(readOnly = true)
    public String resolve(UUID mediaId, String ticket) {
        var actor = MediaAccess.require("media:read");
        MediaAccess.require("practice:read");
        tickets.verifyPublishedQuestionImageTicket(ticket, actor.userId(), mediaId);
        var asset = media.findActive(mediaId);
        // The question bank proof never authorizes videos, drafts or arbitrary private assets.
        if (asset.getStatus() != MediaStatus.READY || !"IMAGE".equals(asset.getMediaType())
                || MediaAccess.simulation(asset.getUploadTargetType())
                || !"PRIVATE".equals(asset.getVisibility()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Image not available for this account");
        return media.sign(asset, 60);
    }
}
