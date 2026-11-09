package com.dts.media.security;

import com.dts.media.api.controller.*;
import com.dts.media.application.dto.*;
import com.dts.media.application.enums.*;
import com.dts.media.application.service.*;
import com.dts.media.domain.entity.*;
import com.dts.media.domain.repository.*;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.GetBucketPolicyArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.*;
import org.springframework.data.domain.Page;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.event.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers={MediaController.class,UploadController.class})
@Import({MediaSecurityConfiguration.class,MediaJwtFilter.class,MediaJwtVerifier.class,MediaService.class,UploadService.class,SimulationPlaybackService.class,MediaStorageGuard.class,MediaApiSecurityTest.Beans.class})
@TestPropertySource(properties="jwt.access-token.secret="+MediaTestTokens.SECRET)
@RecordApplicationEvents
class MediaApiSecurityTest {
    @TestConfiguration static class Beans {
        @Bean @Primary MinioClient minioClient() { return mock(MinioClient.class); }
    }
    @Autowired MockMvc mvc;
    @Autowired MinioClient minio;
    @Autowired ApplicationEvents events;
    @Autowired UploadService uploads;
    @MockBean(name="internalMinioClient") MinioClient internal;
    @MockBean MediaRepository medias;
    @MockBean StorageRepository storages;
    @MockBean UploadSessionRepository sessions;
    @MockBean UploadPolicyRepository policies;
    @MockBean KafkaTemplate<String,UploadVerificationMessage> kafka;
    final UUID owner=UUID.fromString("00000000-0000-4000-8000-000000000001");
    final UUID other=UUID.fromString("00000000-0000-4000-8000-000000000002");
    final UUID id=UUID.fromString("00000000-0000-4000-8000-000000000003");
    final UUID sessionId=UUID.fromString("00000000-0000-4000-8000-000000000004");
    Media media; UploadSession session; UploadPolicy policy;
    static final String UPLOAD="{\"fileName\":\"scenario.mp4\",\"sizeBytes\":1024,\"mimeType\":\"video/mp4\",\"visibility\":\"PRIVATE\",\"targetType\":\"SIMULATION_VIDEO\"}";
    @BeforeEach void fixture() throws Exception {
        reset(minio);
        media=Media.builder().id(id).storageId(UUID.randomUUID()).objectKey("uploads/scenario.mp4").originalFilename("scenario.mp4")
                .extension("mp4").mimeType("video/mp4").sizeBytes(1024L).mediaType("VIDEO").status(MediaStatus.READY)
                .visibility("PRIVATE").uploadTargetType("SIMULATION_VIDEO").createdBy(owner).createdAt(OffsetDateTime.now()).build();
        session=UploadSession.builder().id(sessionId).mediaId(id).uploaderId(owner).status(UploadSessionStatus.PENDING).build();
        policy=UploadPolicy.builder().targetType("SIMULATION_VIDEO").enabled(true).visibility("PRIVATE").maxFileSize(2147483648L)
                .allowedMimeTypes("[\"video/mp4\"]").blockedMimeTypes("[]").build();
        Storage storage=Storage.builder().id(media.getStorageId()).status("ACTIVE").provider("MINIO").bucket("fixture-only").build();
        when(medias.findById(id)).thenReturn(Optional.of(media));
        when(medias.save(any())).thenAnswer(a->a.getArgument(0));
        when(storages.findById(media.getStorageId())).thenReturn(Optional.of(storage));
        when(storages.findByIsDefaultTrue()).thenReturn(Optional.of(storage));
        when(storages.findByNameAndDeletedAtIsNull("private-minio")).thenReturn(Optional.of(storage));
        when(sessions.findByIdForUpdate(sessionId)).thenReturn(Optional.of(session));
        when(policies.findByTargetTypeAndDeletedAtIsNull(anyString())).thenAnswer(a->Optional.of(policy));
        when(medias.findMediasByFilters(any(),any(),any(),any(),any())).thenAnswer(a->Page.empty(a.getArgument(4)));
        when(minio.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class))).thenReturn("http://localhost:9000/fixture-only/scenario.mp4?fixture-only");
        when(internal.getBucketPolicy(any(GetBucketPolicyArgs.class))).thenReturn("{}");
    }
    String student(UUID user,String...rights) {return MediaTestTokens.access(user,List.of("ROLE_STUDENT"),rights);}
    String admin(String...rights) {return MediaTestTokens.access(owner,List.of("ROLE_ADMIN"),rights);}
    String bearer(String token) {return "Bearer "+token;}
    static Stream<Arguments> operations() {return Stream.of(
            Arguments.of("GET","/api/v1/media",""),Arguments.of("PATCH","/api/v1/media/00000000-0000-4000-8000-000000000003","{}"),
            Arguments.of("DELETE","/api/v1/media/00000000-0000-4000-8000-000000000003",""),
            Arguments.of("POST","/api/v1/media/uploads/initialize",UPLOAD),
            Arguments.of("POST","/api/v1/media/uploads/00000000-0000-4000-8000-000000000004/confirm","{}"),
            Arguments.of("POST","/api/v1/media/simulation-playback","{\"ticket\":\"fixture\"}"));}
    MockHttpServletRequestBuilder request(String method,String path,String body) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method),path).contentType("application/json").content(body);
    }
    @ParameterizedTest @MethodSource("operations")
    void protectedOperationsRejectMissingJwtBeforeServices(String method,String path,String body) throws Exception {
        mvc.perform(request(method,path,body).header("X-User-Id",owner)).andExpect(status().isUnauthorized());
        verifyNoInteractions(medias,sessions,policies,storages,minio);
    }
    @ParameterizedTest @MethodSource("operations")
    void administrativeRoleWithoutActionPermissionIsDenied(String method,String path,String body) throws Exception {
        mvc.perform(request(method,path,body).header("Authorization",bearer(admin()))).andExpect(status().isForbidden());
        verifyNoInteractions(medias,sessions,policies,storages,minio);
    }
    @ParameterizedTest @ValueSource(strings={"Bearer invalid","Basic fixture","Bearer e30.eyJzdWIiOiIxIn0."})
    void malformedOrUnsignedJwtIsRejectedEvenOnPublicDetail(String header) throws Exception {
        mvc.perform(get("/api/v1/media/"+id).header("Authorization",header)).andExpect(status().isUnauthorized());
        verifyNoInteractions(medias,minio);
    }
    @Test void publicReadyTheoryImageStillWorksWithoutBearer() throws Exception {
        media.setVisibility("PUBLIC");media.setUploadTargetType("QUESTION");media.setMimeType("image/png");media.setMediaType("IMAGE");
        mvc.perform(get("/api/v1/media/"+id)).andExpect(status().isOk()).andExpect(jsonPath("mediaId").value(id.toString()))
                .andExpect(jsonPath("url").isString()).andExpect(header().string("Cache-Control","no-store"));
    }
    @Test void privateDetailRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/media/"+id)).andExpect(status().isUnauthorized());verifyNoInteractions(minio);
    }
    @Test void learnerCannotReadAnotherUsersPrivateMediaWithOnlyReadPermission() throws Exception {
        mvc.perform(get("/api/v1/media/"+id).header("Authorization",bearer(student(other,"media:read")))).andExpect(status().isForbidden());verifyNoInteractions(minio);
    }
    @Test void ownerCanReadPrivateReadyMedia() throws Exception {
        mvc.perform(get("/api/v1/media/"+id).header("Authorization",bearer(student(owner,"media:read")))).andExpect(status().isOk()).andExpect(jsonPath("url").isString());
    }
    @Test void managementPermissionWithoutAdminRoleDoesNotOpenOtherOwnersMedia() throws Exception {
        mvc.perform(get("/api/v1/media/"+id).header("Authorization",bearer(student(other,"media:read","media:manage")))).andExpect(status().isForbidden());verifyNoInteractions(minio);
    }
    @Test void adminWithReadAndManagementCanInspectOtherOwnersMedia() throws Exception {
        media.setCreatedBy(other);
        mvc.perform(get("/api/v1/media/"+id).header("Authorization",bearer(admin("media:read","media:manage")))).andExpect(status().isOk());
    }
    @Test void ownerCanPollUploadingMetadataWithoutReceivingAUrl() throws Exception {
        media.setStatus(MediaStatus.UPLOADING);
        mvc.perform(get("/api/v1/media/"+id).header("Authorization",bearer(student(owner,"media:read")))).andExpect(status().isOk())
                .andExpect(jsonPath("status").value("UPLOADING")).andExpect(jsonPath("url").doesNotExist());verifyNoInteractions(minio);
    }
    @Test void listIsBoundToJwtSubjectAndIgnoresSpoofedIdentityHeaders() throws Exception {
        mvc.perform(get("/api/v1/media").header("Authorization",bearer(student(owner,"media:read"))).header("X-User-Id",other))
                .andExpect(status().isOk());
        verify(medias).findMediasByFilters(eq(owner),isNull(),isNull(),isNull(),any());
    }
    @Test void listCannotSelectAnotherOwnerThroughQueryParameter() throws Exception {
        mvc.perform(get("/api/v1/media").param("userId",other.toString()).header("Authorization",bearer(student(owner,"media:read"))))
                .andExpect(status().isForbidden());verifyNoInteractions(medias,minio);
    }
    @ParameterizedTest @ValueSource(strings={"PATCH","DELETE"})
    void writesCannotBypassOwnershipByOmittingUserId(String method) throws Exception {
        mvc.perform(request(method,"/api/v1/media/"+id,"{}").header("Authorization",bearer(student(other,"media:update","media:delete"))))
                .andExpect(status().isForbidden());verify(medias,never()).save(any());
    }
    @Test void uploadConfirmationIsBoundToSessionOwner() throws Exception {
        mvc.perform(post("/api/v1/media/uploads/"+sessionId+"/confirm").header("Authorization",bearer(student(other,"media:upload"))))
                .andExpect(status().isForbidden());verify(sessions,never()).save(any());assertEquals(0,events.stream(UploadVerificationEvent.class).count());
    }
    @Test void confirmationIsIdempotentForTheSameOwnerAndPublishesOnce() throws Exception {
        media.setCreatedBy(owner);media.setUploadTargetType("USER");
        for(int i=0;i<2;i++) mvc.perform(post("/api/v1/media/uploads/"+sessionId+"/confirm").header("Authorization",bearer(student(owner,"media:upload"))))
                .andExpect(status().isAccepted()).andExpect(jsonPath("mediaId").value(id.toString()));
        verify(sessions,times(1)).save(session);assertEquals(1,events.stream(UploadVerificationEvent.class).count());
    }
    @ParameterizedTest @ValueSource(strings={"FAILED","CANCELLED","EXPIRED"})
    void terminalUploadCannotBeReconfirmed(String state) throws Exception {
        session.setStatus(UploadSessionStatus.valueOf(state));
        mvc.perform(post("/api/v1/media/uploads/"+sessionId+"/confirm").header("Authorization",bearer(student(owner,"media:upload"))))
                .andExpect(status().isConflict());verify(sessions,never()).save(any());
    }
    @Test void learnerUploadPermissionDoesNotAllowSharedSimulationTargets() throws Exception {
        mvc.perform(post("/api/v1/media/uploads/initialize").contentType("application/json").content(UPLOAD)
                .header("Authorization",bearer(student(owner,"media:upload")))).andExpect(status().isForbidden());
        verifyNoInteractions(policies,medias,minio);
    }
    @Test void simulationUploadRecordsTargetAndRemainsPrivate() throws Exception {
        mvc.perform(post("/api/v1/media/uploads/initialize").contentType("application/json").content(UPLOAD)
                .header("Authorization",bearer(admin("media:upload","media:manage")))).andExpect(status().isCreated());
        var saved=org.mockito.ArgumentCaptor.forClass(Media.class);verify(medias).save(saved.capture());
        assertEquals("SIMULATION_VIDEO",saved.getValue().getUploadTargetType());assertEquals("PRIVATE",saved.getValue().getVisibility());assertEquals(owner,saved.getValue().getCreatedBy());
    }
    @ParameterizedTest @ValueSource(strings={"disabled","deleted-policy","size","mime-substring","extension","visibility","blocked"})
    void invalidSimulationUploadsDoNotCreateRowsOrPresignedUrls(String kind) throws Exception {
        String body=UPLOAD;
        switch(kind) {
            case "disabled" -> policy.setEnabled(false);
            case "deleted-policy" -> policy.setDeletedAt(OffsetDateTime.now());
            case "size" -> policy.setMaxFileSize(100L);
            case "mime-substring" -> body=body.replace("video/mp4","video/mp");
            case "extension" -> body=body.replace("scenario.mp4","scenario.webm");
            case "visibility" -> body=body.replace("PRIVATE","PUBLIC");
            case "blocked" -> policy.setBlockedMimeTypes("[\"video/mp4\"]");
        }
        mvc.perform(post("/api/v1/media/uploads/initialize").contentType("application/json").content(body)
                .header("Authorization",bearer(admin("media:upload","media:manage")))).andExpect(status().isBadRequest());
        verify(medias,never()).save(any());verifyNoInteractions(minio);
    }
    @Test void simulationCannotBeMadePublicByPatch() throws Exception {
        mvc.perform(patch("/api/v1/media/"+id).contentType("application/json").content("{\"visibility\":\"PUBLIC\"}")
                .header("Authorization",bearer(admin("media:update","media:manage")))).andExpect(status().isBadRequest());verify(medias,never()).save(any());
    }
    @Test void signingFailureIsAnExplicitErrorRatherThanSuccessfulNullUrl() throws Exception {
        when(minio.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class))).thenThrow(new RuntimeException("fixture only"));
        mvc.perform(get("/api/v1/media/"+id).header("Authorization",bearer(student(owner,"media:read")))).andExpect(status().isServiceUnavailable());
    }
    @Test void validPracticeTicketOpensOnlyTheAttestedPrivateSimulationMedia() throws Exception {
        String ticket=MediaTestTokens.ticket(MediaTestTokens.ticketClaims(other,id));
        mvc.perform(post("/api/v1/media/simulation-playback").contentType("application/json").content("{\"ticket\":\""+ticket+"\"}")
                .header("Authorization",bearer(student(other,"media:read","simulations:read")))).andExpect(status().isOk())
                .andExpect(jsonPath("mediaId").value(id.toString())).andExpect(jsonPath("bankVersion").value("simulation-v1"))
                .andExpect(jsonPath("expiredAt").isString()).andExpect(jsonPath("url").isString());
    }
    @ParameterizedTest @ValueSource(strings={"subject","expired","future-issued","too-long","audience","issuer","purpose","bank","attempt","target","signature"})
    void invalidTicketsNeverReachTheSigner(String kind) throws Exception {
        Map<String,Object> claims=MediaTestTokens.ticketClaims(other,id);
        switch(kind) {
            case "subject" -> claims.put("sub",owner.toString());
            case "expired" -> claims.put("exp",new Date(1000));
            case "future-issued" -> claims.put("iat",new Date(System.currentTimeMillis()+300000));
            case "too-long" -> claims.put("exp",new Date(System.currentTimeMillis()+300000));
            case "audience" -> claims.put("aud",List.of("other-service"));
            case "issuer" -> claims.put("iss","other-service");
            case "purpose" -> claims.put("purpose","OTHER");
            case "bank" -> claims.remove("bankVersion");
            case "attempt" -> claims.remove("attemptId");
            case "target" -> claims.put("targetType","USER");
        }
        String ticket=MediaTestTokens.ticket(claims);if(kind.equals("signature"))ticket=ticket.substring(0,ticket.lastIndexOf('.')+1)+"invalid";
        mvc.perform(post("/api/v1/media/simulation-playback").contentType("application/json").content("{\"ticket\":\""+ticket+"\"}")
                .header("Authorization",bearer(student(other,"media:read","simulations:read")))).andExpect(status().isForbidden());
        verifyNoInteractions(minio,medias);
    }
    @Test void ordinaryPersonalUploadCannotBeOpenedWithASimulationTicket() throws Exception {
        media.setUploadTargetType("USER");String ticket=MediaTestTokens.ticket(MediaTestTokens.ticketClaims(other,id));
        mvc.perform(post("/api/v1/media/simulation-playback").contentType("application/json").content("{\"ticket\":\""+ticket+"\"}")
                .header("Authorization",bearer(student(other,"media:read","simulations:read")))).andExpect(status().isForbidden());verifyNoInteractions(minio);
    }
    @Test void workerDoesNotProcessAMessageForADifferentSessionMediaPair() {
        session.setStatus(UploadSessionStatus.UPLOADING);uploads.verifyUpload(sessionId,UUID.randomUUID());
        verifyNoInteractions(internal);verify(medias,never()).save(any());verify(sessions,never()).save(any());
    }
    @Test void publicBucketCannotBeUsedToIssuePrivateMediaUrls() throws Exception {
        when(internal.getBucketPolicy(any(GetBucketPolicyArgs.class))).thenReturn("{\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":\"*\",\"Action\":\"s3:GetObject\"}]}");
        mvc.perform(get("/api/v1/media/"+id).header("Authorization",bearer(student(owner,"media:read")))).andExpect(status().isServiceUnavailable());
        verifyNoInteractions(minio);
    }
    @Test void simulationUploadCannotUseAPublicStorageBucket() throws Exception {
        when(internal.getBucketPolicy(any(GetBucketPolicyArgs.class))).thenReturn("{\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"*\"}}]}");
        mvc.perform(post("/api/v1/media/uploads/initialize").contentType("application/json").content(UPLOAD)
                .header("Authorization",bearer(admin("media:upload","media:manage")))).andExpect(status().isServiceUnavailable());
        verify(medias,never()).save(any());verifyNoInteractions(minio);
    }
}
