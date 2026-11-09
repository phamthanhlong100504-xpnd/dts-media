package com.dts.media.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MediaJwtVerifierTest {
    final MediaJwtVerifier verifier = new MediaJwtVerifier(MediaTestTokens.SECRET,"dts-identity-service");
    final UUID user = UUID.randomUUID();
    @Test void validatesSignatureIssuerSubjectAndMapsRawPermissionsWithoutEscalation() {
        var actor = verifier.verify(MediaTestTokens.access(user,List.of("ROLE_STUDENT"),"media:read","media:read","simulations:read"));
        assertEquals(user,actor.userId()); assertEquals(Set.of("media:read","simulations:read"),actor.permissions()); assertFalse(actor.manager());
    }
    @Test void identityV16ExamSessionPermissionWithUnderscoreIsAcceptedWithoutPrivilegeEscalation() {
        var actor = verifier.verify(MediaTestTokens.access(user, List.of("ROLE_ADMIN"),
                "media:read", "exam_session:read", "official-exams:release-result"));
        assertEquals(user, actor.userId());
        assertTrue(actor.permissions().contains("exam_session:read"));
        assertFalse(actor.manager());
    }
    @Test void malformedPermissionStillRejectedAfterUnderscoreCompatibility() {
        assertThrows(IllegalArgumentException.class,
                () -> verifier.verify(MediaTestTokens.access(user, List.of("ROLE_ADMIN"),
                        "exam_session:read", "bad.permission:read")));
    }
    @Test void administrativeRoleAloneDoesNotGrantMediaManagement() {
        assertFalse(verifier.verify(MediaTestTokens.access(user,List.of("ROLE_ADMIN"),"media:read")).manager());
        assertFalse(verifier.verify(MediaTestTokens.access(user,List.of("ROLE_STUDENT"),"media:manage")).manager());
        assertTrue(verifier.verify(MediaTestTokens.access(user,List.of("ROLE_ADMIN"),"media:manage")).manager());
    }
    @Test void wrongSigningKeyIsRejected() {
        String token=Jwts.builder().issuer("dts-identity-service").subject(user.toString()).issuedAt(new Date()).expiration(new Date(System.currentTimeMillis()+60000))
                .signWith(Keys.hmacShaKeyFor(new byte[64])).compact();
        assertThrows(Exception.class,()->verifier.verify(token));
    }
    @ParameterizedTest @ValueSource(strings={"issuer","expired","expiry-missing","subject","future-issued","refresh","permissions-shape","permission-prefix","roles-shape"})
    void invalidClaimsAreRejected(String kind) {
        Map<String,Object> claims=new HashMap<>();claims.put("iss","dts-identity-service");claims.put("sub",user.toString());
        claims.put("iat",new Date());claims.put("exp",new Date(System.currentTimeMillis()+60000));
        claims.put("roles",List.of("ROLE_STUDENT"));claims.put("permissions",List.of("media:read"));
        switch(kind) {
            case "issuer" -> claims.put("iss","other-service");
            case "expired" -> claims.put("exp",new Date(1000));
            case "expiry-missing" -> claims.remove("exp");
            case "subject" -> claims.put("sub","1-1-1-1-1");
            case "future-issued" -> claims.put("iat",new Date(System.currentTimeMillis()+300000));
            case "refresh" -> claims.put("type","refresh");
            case "permissions-shape" -> claims.put("permissions","media:read");
            case "permission-prefix" -> claims.put("permissions",List.of("PERM_media:read"));
            case "roles-shape" -> claims.put("roles",List.of(123));
        }
        String token=Jwts.builder().claims(claims).signWith(MediaTestTokens.ACCESS).compact();
        assertThrows(Exception.class,()->verifier.verify(token));
    }
    @Test void unsignedAndPayloadOnlyTokensAreRejected() {
        for(String token:List.of("e30.eyJzdWIiOiIxIn0.","a.b","not-a-jwt")) assertThrows(Exception.class,()->verifier.verify(token));
    }
    @Test void playbackTicketCannotBeUsedAsAnIdentityAccessTokenOrViceVersa() {
        String ticket=MediaTestTokens.ticket(MediaTestTokens.ticketClaims(user,UUID.randomUUID()));
        assertThrows(Exception.class,()->verifier.verify(ticket));
        assertThrows(Exception.class,()->verifier.playbackClaims(MediaTestTokens.access(user,List.of("ROLE_STUDENT"),"media:read")));
        assertEquals(user.toString(),verifier.playbackClaims(ticket).getSubject());
    }
    @Test void blankConfigurationFailsClosedWithoutEchoingTheSecret() {
        assertThrows(IllegalStateException.class,()->new MediaJwtVerifier("","dts-identity-service"));
    }
}
