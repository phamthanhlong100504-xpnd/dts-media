package com.dts.media.application.service;

import com.dts.media.api.controller.SimulationCatalogController;
import com.dts.media.application.enums.MediaStatus;
import com.dts.media.domain.entity.Media;
import com.dts.media.security.*;
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
import java.time.OffsetDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SimulationCatalogController.class)
@Import({MediaSecurityConfiguration.class,MediaJwtFilter.class,MediaJwtVerifier.class,SimulationCatalogTicketVerifier.class,SimulationCatalogService.class})
@TestPropertySource(properties="jwt.access-token.secret="+SimulationCatalogTestTokens.SECRET)
class SimulationCatalogSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired SimulationCatalogService service;
    @MockBean MediaService mediaService;
    final UUID user=UUID.randomUUID(), id=UUID.randomUUID(), scenario=UUID.randomUUID();
    static final String PATH="/api/v1/media/simulation-catalog-thumbnail";
    Media media;
    @BeforeEach void fixture() {
        media=Media.builder().id(id).createdBy(UUID.randomUUID()).originalFilename("scenario.webp")
                .extension("webp").mimeType("image/webp").mediaType("IMAGE").status(MediaStatus.READY)
                .visibility("PRIVATE").uploadTargetType("SIMULATION_THUMBNAIL").build();
        when(mediaService.findActive(id)).thenReturn(media);
        when(mediaService.sign(media,300)).thenReturn("http://localhost:9000/fixture/thumbnail.webp?fixture-only");
    }
    String ticket() {return SimulationCatalogTestTokens.ticket(SimulationCatalogTestTokens.claims(user,id,scenario));}
    String bearer(String...rights) {return "Bearer "+SimulationCatalogTestTokens.access(user,rights);}
    String body(String ticket) {return "{\"ticket\":\""+ticket+"\"}";}
    @Test void noJwtIsDeniedBeforeMetadataLookup() throws Exception {
        mvc.perform(post(PATH).contentType("application/json").content(body(ticket()))).andExpect(status().isUnauthorized());
        verifyNoInteractions(mediaService);
    }
    @Test void forgedJwtIsDenied() throws Exception {
        mvc.perform(post(PATH).contentType("application/json").content(body(ticket())).header("Authorization","Bearer invalid"))
                .andExpect(status().isUnauthorized());verifyNoInteractions(mediaService);
    }
    @ParameterizedTest @ValueSource(strings={"media:read","simulations:read","media:manage"})
    void bothReadPermissionsAreRequired(String right) throws Exception {
        mvc.perform(post(PATH).contentType("application/json").content(body(ticket())).header("Authorization",bearer(right)))
                .andExpect(status().isForbidden());verifyNoInteractions(mediaService);
    }
    @Test void learnerCanReadOnlyTheAttestedThumbnailBeforeCreatingAnAttempt() throws Exception {
        var before=OffsetDateTime.now();
        var response=mvc.perform(post(PATH).contentType("application/json").content(body(ticket()))
                .header("Authorization",bearer("media:read","simulations:read")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("mediaId").value(id.toString())).andExpect(jsonPath("scenarioId").value(scenario.toString()))
                .andExpect(jsonPath("bankVersion").value("dts-simulation-120-card01-v1")).andExpect(jsonPath("url").isString())
                .andExpect(jsonPath("attemptId").doesNotExist()).andReturn();
        var json=new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.getResponse().getContentAsString());
        assertTrue(OffsetDateTime.parse(json.get("expiredAt").asText()).isAfter(before.plusSeconds(290)));
        verify(mediaService).findActive(id);verify(mediaService).sign(media,300);
    }
    @Test void attestedLegacyNullTargetIsAllowedWithoutChangingMetadata() throws Exception {
        media.setUploadTargetType(null);
        mvc.perform(post(PATH).contentType("application/json").content(body(ticket())).header("Authorization",bearer("media:read","simulations:read")))
                .andExpect(status().isOk());assertNull(media.getUploadTargetType());
    }
    @Test void ticketOfAnotherUserIsRejectedEvenWithUserHint() throws Exception {
        mvc.perform(post(PATH).contentType("application/json").content(body(ticket())).header("X-User-Id",user)
                .header("Authorization","Bearer "+SimulationCatalogTestTokens.access(UUID.randomUUID(),"media:read","simulations:read")))
                .andExpect(status().isForbidden());verifyNoInteractions(mediaService);
    }
    @ParameterizedTest @ValueSource(strings={"PUBLIC","VIDEO","target","mime","filename","status"})
    void broaderOrUnreadyMediaIsDeniedBeforeSigning(String kind) throws Exception {
        int expected=403;
        switch(kind) {
            case "PUBLIC" -> media.setVisibility("PUBLIC");
            case "VIDEO" -> media.setMediaType("VIDEO");
            case "target" -> media.setUploadTargetType("SIMULATION_VIDEO");
            case "mime" -> {media.setMimeType("video/mp4");expected=400;}
            case "filename" -> {media.setOriginalFilename("scenario.mp4");expected=400;}
            case "status" -> {media.setStatus(MediaStatus.UPLOADING);expected=409;}
        }
        mvc.perform(post(PATH).contentType("application/json").content(body(ticket())).header("Authorization",bearer("media:read","simulations:read")))
                .andExpect(status().is(expected));verify(mediaService,never()).sign(any(),anyInt());
    }
    @Test void invalidTicketIsForbiddenBeforeMetadataLookup() throws Exception {
        mvc.perform(post(PATH).contentType("application/json").content(body("invalid")).header("Authorization",bearer("media:read","simulations:read")))
                .andExpect(status().isForbidden());verifyNoInteractions(mediaService);
    }
    @Test void storageGuardFailureDoesNotReturnAnyUrl() throws Exception {
        when(mediaService.sign(media,300)).thenThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"Media storage unavailable"));
        mvc.perform(post(PATH).contentType("application/json").content(body(ticket())).header("Authorization",bearer("media:read","simulations:read")))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("url").doesNotExist());
    }
    @Test void callingServiceDirectlyStillRequiresAuthentication() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.resolve(ticket()));verifyNoInteractions(mediaService);
    }
}
