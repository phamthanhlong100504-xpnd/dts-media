package com.dts.media.application.service;

import com.dts.media.api.controller.SimulationExamMediaController;
import com.dts.media.application.enums.MediaStatus;
import com.dts.media.domain.entity.Media;
import com.dts.media.domain.repository.MediaRepository;
import com.dts.media.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.OffsetDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SimulationExamMediaController.class)
@Import({MediaSecurityConfiguration.class,MediaJwtFilter.class,MediaJwtVerifier.class,SimulationExamTicketVerifier.class,SimulationExamMediaService.class})
@TestPropertySource(properties="jwt.access-token.secret="+SimulationExamTestTokens.SECRET)
class SimulationExamMediaApiTest {
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired SimulationExamMediaService service;
    @MockBean MediaRepository medias;@MockBean MediaService mediaService;@MockBean SimulationExamObjectProbe objects;
    final UUID user=UUID.randomUUID(),id=UUID.randomUUID(),session=UUID.randomUUID();Media media;
    static final String PATH="/api/v1/media/simulation-exam/";
    @BeforeEach void fixture() {
        media=Media.builder().id(id).createdBy(UUID.randomUUID()).storageId(UUID.randomUUID())
                .objectKey("uploads/2026/11/02/"+id+".mp4").originalFilename("fixture.mp4").extension("mp4")
                .mimeType("video/mp4").mediaType("VIDEO").status(MediaStatus.READY).sizeBytes(123L)
                .visibility("PRIVATE").uploadTargetType("SIMULATION_VIDEO").build();
        when(medias.findById(id)).thenReturn(Optional.of(media));
        when(objects.inspect(any(),any())).thenReturn(new SimulationExamObjectProbe.Observation(true,SimulationExamTestTokens.ETAG,"READY"));
        when(mediaService.sign(media,300)).thenReturn("http://localhost:9000/media/uploads/2026/11/02/"+id+".mp4?fixture-only");
    }
    String token(String kind){return SimulationExamTestTokens.ticket(kind,SimulationExamTestTokens.claims(kind,user,id,session));}
    String body(String token)throws Exception{return json.writeValueAsString(Map.of("ticket",token));}
    String auth(String...rights){return "Bearer "+SimulationExamTestTokens.access(user,false,rights);}
    String auth(){return auth("media:read","simulations:read","simulations:exam");}
    @ParameterizedTest @ValueSource(strings={"eligibility","playback","review-playback"})
    void missingJwtCannotReachMediaMetadata(String endpoint)throws Exception {
        mvc.perform(post(PATH+endpoint).contentType("application/json").content(body("invalid"))).andExpect(status().isUnauthorized());
        verifyNoInteractions(medias,objects,mediaService);
    }
    @ParameterizedTest @ValueSource(strings={"eligibility","playback","review-playback"})
    void forgedJwtCannotReachMediaMetadata(String endpoint)throws Exception {
        mvc.perform(post(PATH+endpoint).contentType("application/json").content(body("invalid")).header("Authorization","Bearer invalid"))
                .andExpect(status().isUnauthorized());verifyNoInteractions(medias,objects,mediaService);
    }
    @ParameterizedTest @ValueSource(strings={"no-exam","no-read","no-media","theory-only","admin-without-exam"})
    void exactPermissionsAreRequiredWithoutRoleOrTheoryBypass(String kind)throws Exception {
        String bearer=switch(kind){
            case "no-exam"->auth("media:read","simulations:read");case "no-read"->auth("media:read","simulations:exam");
            case "no-media"->auth("simulations:read","simulations:exam");case "theory-only"->auth("practice:exam","simulations:learn","simulations:read","media:read");
            default->"Bearer "+SimulationExamTestTokens.access(user,true,"media:read","simulations:read");};
        mvc.perform(post(PATH+"eligibility").contentType("application/json").content(body(token("eligibility"))).header("Authorization",bearer))
                .andExpect(status().isForbidden());verifyNoInteractions(medias,objects,mediaService);
    }
    @Test void eligibilityNeedsNoLearningOrManagementGrantAndIssuesNoUrl()throws Exception {
        mvc.perform(post(PATH+"eligibility").contentType("application/json").content(body(token("eligibility"))).header("Authorization",auth()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("items[0].mediaId").value(id.toString())).andExpect(jsonPath("items[0].ready").value(true))
                .andExpect(jsonPath("items[0].objectEtag").value(SimulationExamTestTokens.ETAG))
                .andExpect(jsonPath("items[0].url").doesNotExist()).andExpect(jsonPath("manifestSha256").value(SimulationExamTestTokens.MANIFEST));
        verify(mediaService,never()).sign(any(),anyInt());
    }
    @ParameterizedTest @ValueSource(strings={"playback","review-playback"})
    void scopedPlaybackBindsSessionPositionNonceAndReturnsNoSourceLabels(String endpoint)throws Exception {
        String kind=endpoint.equals("playback")?"playback":"review";var claims=SimulationExamTestTokens.claims(kind,user,id,session);
        var before=OffsetDateTime.now();
        var response=mvc.perform(post(PATH+endpoint).contentType("application/json").content(body(SimulationExamTestTokens.ticket(kind,claims))).header("Authorization",auth()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("sessionId").value(session.toString())).andExpect(jsonPath("position").value(1))
                .andExpect(jsonPath("playbackEventId").value(claims.get("playbackEventId"))).andExpect(jsonPath("mimeType").value("video/mp4"))
                .andExpect(jsonPath("durationMs").value(27000)).andExpect(jsonPath("usage").value(kind.equals("review")?"REVIEW":"CURRENT"))
                .andExpect(jsonPath("scenarioId").doesNotExist()).andExpect(jsonPath("title").doesNotExist()).andExpect(jsonPath("chapterName").doesNotExist())
                .andExpect(jsonPath("score").doesNotExist()).andExpect(jsonPath("scoringTimeline").doesNotExist()).andReturn();
        var result=json.readTree(response.getResponse().getContentAsString());assertTrue(OffsetDateTime.parse(result.get("expiredAt").asText()).isAfter(before.plusSeconds(290)));
        assertFalse(response.getResponse().getContentAsString().contains("fixture.mp4"));verify(mediaService).sign(media,300);
    }
    @Test void otherAccountAndUserHintCannotUseTicket()throws Exception {
        mvc.perform(post(PATH+"playback").contentType("application/json").content(body(token("playback"))).header("X-User-Id",user)
                .header("Authorization","Bearer "+SimulationExamTestTokens.access(UUID.randomUUID(),false,"media:read","simulations:read","simulations:exam")))
                .andExpect(status().isForbidden());verifyNoInteractions(medias,objects,mediaService);
    }
    @Test void reviewTicketCannotBeUsedAsActivePlayback()throws Exception {
        mvc.perform(post(PATH+"playback").contentType("application/json").content(body(token("review"))).header("Authorization",auth()))
                .andExpect(status().isForbidden());verifyNoInteractions(medias,objects,mediaService);
    }
    @ParameterizedTest @ValueSource(strings={"PUBLIC","type","target","status","deleted","mime","name","path","checksum"})
    void invalidOrChangedMetadataCannotIssuePlayback(String kind)throws Exception {
        switch(kind){case "PUBLIC"->media.setVisibility("PUBLIC");case "type"->media.setMediaType("IMAGE");case "target"->media.setUploadTargetType("QUESTION");
            case "status"->media.setStatus(MediaStatus.UPLOADING);case "deleted"->media.setDeletedAt(OffsetDateTime.now());case "mime"->media.setMimeType("video/webm");
            case "name"->media.setOriginalFilename("different.mp4");case "path"->media.setObjectKey("scenario-number-1.mp4");case "checksum"->media.setChecksum("c".repeat(64));}
        mvc.perform(post(PATH+"playback").contentType("application/json").content(body(token("playback"))).header("Authorization",auth()))
                .andExpect(status().isConflict()).andExpect(jsonPath("url").doesNotExist());verifyNoInteractions(objects);verify(mediaService,never()).sign(any(),anyInt());
    }
    @Test void attestedLegacyNullTargetStillWorksWithoutMetadataMutation()throws Exception {
        media.setUploadTargetType(null);media.setSizeBytes(0L);
        mvc.perform(post(PATH+"playback").contentType("application/json").content(body(token("playback"))).header("Authorization",auth())).andExpect(status().isOk());
        assertNull(media.getUploadTargetType());assertEquals(0L,media.getSizeBytes());verify(medias,never()).save(any());
    }
    @Test void changedObjectEtagCannotUsePinnedSession()throws Exception {
        when(objects.inspect(any(),any())).thenReturn(new SimulationExamObjectProbe.Observation(true,"changedetag","READY"));
        mvc.perform(post(PATH+"playback").contentType("application/json").content(body(token("playback"))).header("Authorization",auth())).andExpect(status().isConflict());
        verify(mediaService,never()).sign(any(),anyInt());
    }
    @Test void missingCandidateIsReportedUnreadyWithoutUrl()throws Exception {
        when(medias.findById(id)).thenReturn(Optional.empty());
        mvc.perform(post(PATH+"eligibility").contentType("application/json").content(body(token("eligibility"))).header("Authorization",auth()))
                .andExpect(status().isOk()).andExpect(jsonPath("items[0].ready").value(false)).andExpect(jsonPath("items[0].reason").value("MISSING"))
                .andExpect(jsonPath("items[0].objectEtag").isEmpty());verifyNoInteractions(objects,mediaService);
    }
    @Test void privatePolicyOrStorageFailureReturns503WithoutUrl()throws Exception {
        when(objects.inspect(any(),any())).thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Simulation exam storage unavailable"));
        mvc.perform(post(PATH+"playback").contentType("application/json").content(body(token("playback"))).header("Authorization",auth()))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("url").doesNotExist());verify(mediaService,never()).sign(any(),anyInt());
    }
    @ParameterizedTest @ValueSource(strings={"{}","null","{\"ticket\":42}","{\"ticket\":\"\"}","{\"ticket\":\"invalid\",\"userId\":\"spoofed\"}"})
    void bodyCannotAddActorOrDirectMediaSelection(String value)throws Exception {
        mvc.perform(post(PATH+"playback").contentType("application/json").content(value).header("Authorization",auth()))
                .andExpect(status().isBadRequest());verifyNoInteractions(medias,objects,mediaService);
    }
    @Test void directServiceCallStillRequiresAllPermissions() {
        MediaTestActors.set(user,Set.of("ROLE_STUDENT"),"media:read","simulations:read");
        try{assertThrows(SecurityException.class,()->service.playback(token("playback"),false));verifyNoInteractions(medias,objects,mediaService);}
        finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    }
}
