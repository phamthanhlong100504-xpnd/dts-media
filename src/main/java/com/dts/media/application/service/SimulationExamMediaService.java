package com.dts.media.application.service;

import com.dts.media.api.response.SimulationExamMediaModel.*;
import com.dts.media.application.enums.MediaStatus;
import com.dts.media.domain.entity.Media;
import com.dts.media.domain.repository.MediaRepository;
import com.dts.media.security.MediaAccess;
import com.dts.media.security.SimulationExamTicketVerifier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
@RequiredArgsConstructor
public class SimulationExamMediaService {
    private final SimulationExamTicketVerifier tickets;
    private final MediaRepository medias;
    private final MediaService mediaService;
    private final SimulationExamObjectProbe objects;

    private UUID actor() {
        var actor=MediaAccess.require("simulations:exam");MediaAccess.require("simulations:read");MediaAccess.require("media:read");return actor.userId();
    }
    @Transactional(readOnly=true)
    public EligibilityResponse eligibility(String ticket) {
        var scope=tickets.eligibility(ticket,actor());List<EligibilityItem> result=new ArrayList<>();
        for(Asset asset:scope.candidates()) {
            Media media=medias.findById(asset.mediaId()).orElse(null);String reason=metadata(media,asset);String etag=null;
            if(reason==null){var observation=objects.inspect(media,asset);reason=observation.reason();etag=observation.etag();}
            boolean ready="READY".equals(reason);result.add(new EligibilityItem(asset.mediaId(),ready,asset.durationMs(),asset.videoTrackDurationMs(),ready?etag:null,reason));
        }
        return new EligibilityResponse(scope.bankVersion(),scope.manifestSha256(),List.copyOf(result));
    }
    @Transactional(readOnly=true)
    public PlaybackResponse playback(String ticket,boolean review) {
        var scope=tickets.playback(ticket,actor(),review);var media=medias.findById(scope.asset().mediaId()).orElse(null);
        String reason=metadata(media,scope.asset());if(reason!=null)throw notReady();
        var observation=objects.inspect(media,scope.asset());
        if(!observation.ready()||!scope.objectEtag().equals(observation.etag()))throw notReady();
        var issued=OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
        String url=mediaService.sign(media,300);
        return new PlaybackResponse(scope.sessionId(),scope.position(),scope.playbackEventId(),scope.bankVersion(),scope.manifestSha256(),
                media.getId(),url,issued.plusSeconds(300),"video/mp4",scope.asset().durationMs(),review?"REVIEW":"CURRENT");
    }
    private String metadata(Media media,Asset asset) {
        if(media==null||media.getDeletedAt()!=null)return "MISSING";
        if(media.getStatus()!=MediaStatus.READY)return "NOT_READY";
        if(!"PRIVATE".equals(media.getVisibility())||!"VIDEO".equals(media.getMediaType())
                ||(media.getUploadTargetType()!=null&&!"SIMULATION_VIDEO".equals(media.getUploadTargetType())))return "SCOPE_MISMATCH";
        if(!asset.fileName().equals(media.getOriginalFilename())||!"video/mp4".equals(media.getMimeType()))return "ASSET_MISMATCH";
        // Prevent source labels from leaking through a presigned object path during the exam.
        if(media.getObjectKey()==null||!media.getObjectKey().matches("uploads/[0-9]{4}/[0-9]{2}/[0-9]{2}/"+media.getId()+"\\.mp4"))return "OPAQUE_PATH_REQUIRED";
        String sha=media.getChecksum();
        if(sha!=null&&sha.matches("[a-fA-F0-9]{64}")&&!asset.sha256().equalsIgnoreCase(sha))return "ASSET_MISMATCH";
        return null;
    }
    private ResponseStatusException notReady(){return new ResponseStatusException(HttpStatus.CONFLICT,"Simulation exam video unavailable or changed");}
}
