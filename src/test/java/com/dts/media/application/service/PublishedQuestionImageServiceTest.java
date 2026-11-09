package com.dts.media.application.service;

import com.dts.media.application.enums.MediaStatus;
import com.dts.media.domain.entity.Media;
import com.dts.media.security.MediaActor;
import com.dts.media.security.MediaJwtVerifier;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublishedQuestionImageServiceTest {
    private final MediaService media=mock(MediaService.class);
    private final MediaJwtVerifier tickets=mock(MediaJwtVerifier.class);
    private final PublishedQuestionImageService service=new PublishedQuestionImageService(media,tickets);
    private final UUID actor=UUID.randomUUID();
    private final UUID image=UUID.randomUUID();

    @AfterEach void clear(){ SecurityContextHolder.clearContext(); }

    private void auth(boolean canLearn){
        var permissions=canLearn?Set.of("media:read","practice:read"):Set.of("media:read");
        var principal=new MediaActor(actor,Set.of("ROLE_STUDENT"),permissions);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null, java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("PERM_media:read"))));
    }
    private Media asset(String status,String kind,String visibility,String target){
        return Media.builder().id(image).status(MediaStatus.valueOf(status))
            .mediaType(kind).visibility(visibility).uploadTargetType(target).build();
    }

    @Test void learnerWithPublishedProofCanReadOnlyReadyPrivateQuestionImage() {
        auth(true);
        when(media.findActive(image)).thenReturn(asset("READY","IMAGE","PRIVATE","QUESTION"));
        when(media.sign(any(),eq(60))).thenReturn("https://example.invalid/signed-object");
        assertEquals("https://example.invalid/signed-object",service.resolve(image,"valid-proof"));
        verify(tickets).verifyPublishedQuestionImageTicket("valid-proof",actor,image);
        verify(media).sign(any(),eq(60));
    }

    @Test void draftVideoNotReadyOrUnscopedUserCannotGetSignedUrl() {
        auth(true);
        for(var bad:new Media[]{asset("READY","VIDEO","PRIVATE","SIMULATION_VIDEO"),
                asset("READY","IMAGE","PRIVATE","SIMULATION_THUMBNAIL"),
                asset("READY","IMAGE","PUBLIC","QUESTION"),
                asset("UPLOADING","IMAGE","PRIVATE","QUESTION")}){
            when(media.findActive(image)).thenReturn(bad);
            assertThrows(ResponseStatusException.class,()->service.resolve(image,"valid-proof"));
        }
        verify(media,never()).sign(any(),anyInt());
        SecurityContextHolder.clearContext();auth(false);
        assertThrows(SecurityException.class,()->service.resolve(image,"valid-proof"));
        verify(media,never()).sign(any(),anyInt());
    }
}
