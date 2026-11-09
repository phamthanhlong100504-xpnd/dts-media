package com.dts.media.security;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SimulationExamTicketVerifierTest {
    final SimulationExamTicketVerifier verifier=new SimulationExamTicketVerifier(SimulationExamTestTokens.SECRET);
    final UUID user=UUID.randomUUID(),media=UUID.randomUUID(),session=UUID.randomUUID();
    Map<String,Object> claims(String kind){return SimulationExamTestTokens.claims(kind,user,media,session);}
    String ticket(String kind,Map<String,Object> c){return SimulationExamTestTokens.ticket(kind,c);}
    void rejects(String token) {
        var e=assertThrows(ResponseStatusException.class,()->verifier.eligibility(token,user));assertEquals(403,e.getStatusCode().value());
        if(token!=null&&!token.isEmpty())assertFalse(e.getReason().contains(token));
    }
    @Test void eligibilityRetainsOnlySignedBankAndCandidateBindings() {
        var result=verifier.eligibility(ticket("eligibility",claims("eligibility")),user);
        assertEquals(SimulationExamTestTokens.BANK,result.bankVersion());assertEquals(SimulationExamTestTokens.MANIFEST,result.manifestSha256());
        assertEquals(1,result.candidates().size());assertEquals(media,result.candidates().getFirst().mediaId());assertEquals(27000,result.candidates().getFirst().durationMs());
    }
    @ParameterizedTest @ValueSource(strings={"playback","review"})
    void playbackRetainsOwnerSessionPositionNonceAssetAndEtag(String kind) {
        var c=claims(kind);var scope=verifier.playback(ticket(kind,c),user,kind.equals("review"));
        assertEquals(session,scope.sessionId());assertEquals(1,scope.position());assertEquals(UUID.fromString((String)c.get("playbackEventId")),scope.playbackEventId());
        assertEquals(media,scope.asset().mediaId());assertEquals(SimulationExamTestTokens.ETAG,scope.objectEtag());assertEquals(kind.equals("review"),scope.review());
    }
    @ParameterizedTest @ValueSource(strings={"iss","aud","sub","iat","exp","jti","purpose","bankVersion","manifestSha256","targetType","candidates"})
    void eligibilityRequiresEveryClaim(String key) {var c=claims("eligibility");c.remove(key);rejects(ticket("eligibility",c));}
    @ParameterizedTest @ValueSource(strings={"issuer","audience","multi-audience","other-user","future","expired","long-life","jti","version","manifest","target","purpose","unexpected-actor","unexpected-score","unexpected-attempt"})
    void invalidClaimsCannotBroadenEligibility(String kind) {
        var c=claims("eligibility");
        switch(kind) {
            case "issuer"->c.put("iss","other-service");case "audience"->c.put("aud",List.of("other-service"));
            case "multi-audience"->c.put("aud",List.of("dts-media","other-service"));case "other-user"->c.put("sub",UUID.randomUUID().toString());
            case "future"->{c.put("iat",new Date(System.currentTimeMillis()+10000));c.put("exp",new Date(System.currentTimeMillis()+20000));}
            case "expired"->{c.put("iat",new Date(1000));c.put("exp",new Date(2000));}case "long-life"->c.put("exp",new Date(System.currentTimeMillis()+120000));
            case "jti"->c.put("jti","not-a-uuid");case "version"->c.put("bankVersion","../current");case "manifest"->c.put("manifestSha256","not-sha256");
            case "target"->c.put("targetType","SIMULATION_THUMBNAIL");case "purpose"->c.put("purpose","SIMULATION_LEARNING");
            case "unexpected-actor"->c.put("userId",user.toString());case "unexpected-score"->c.put("score",5);case "unexpected-attempt"->c.put("attemptId",UUID.randomUUID().toString());
        }
        rejects(ticket("eligibility",c));
    }
    @ParameterizedTest @ValueSource(strings={"empty","too-many","duplicate","not-list","null-item"})
    void eligibilityBatchIsBoundedAndUnique(String kind) {
        var c=claims("eligibility");var a=SimulationExamTestTokens.asset(media);
        switch(kind) {
            case "empty"->c.put("candidates",List.of());case "too-many"->c.put("candidates",Collections.nCopies(17,a));
            case "duplicate"->c.put("candidates",List.of(a,a));case "not-list"->c.put("candidates",a);case "null-item"->c.put("candidates",Collections.singletonList(null));
        }
        rejects(ticket("eligibility",c));
    }
    @ParameterizedTest @ValueSource(strings={"mediaId","fileName","sha256","durationMs","videoTrackDurationMs","extra"})
    void malformedAssetIsNotAccepted(String field) {
        var c=claims("eligibility");var a=SimulationExamTestTokens.asset(media);
        switch(field) {
            case "mediaId"->a.put(field,"1-1-1-1-1");case "fileName"->a.put(field,"../fixture.mp4");case "sha256"->a.put(field,"wrong");
            case "durationMs"->a.put(field,0);case "videoTrackDurationMs"->a.put(field,1.5);case "extra"->a.put("score",5);
        }
        c.put("candidates",List.of(a));rejects(ticket("eligibility",c));
    }
    @ParameterizedTest @ValueSource(strings={"sessionId","position","playbackEventId","asset","objectEtag"})
    void playbackRequiresEveryPositionBinding(String field) {
        var c=claims("playback");c.remove(field);
        assertThrows(ResponseStatusException.class,()->verifier.playback(ticket("playback",c),user,false));
    }
    @ParameterizedTest @ValueSource(strings={"zero","future-position","fraction","etag"})
    void playbackRejectsInvalidPositionOrEtag(String kind) {
        var c=claims("playback");
        switch(kind){case "zero"->c.put("position",0);case "future-position"->c.put("position",11);case "fraction"->c.put("position",1.5);case "etag"->c.put("objectEtag","invalid/etag");}
        assertThrows(ResponseStatusException.class,()->verifier.playback(ticket("playback",c),user,false));
    }
    @Test void signingDomainsAndEndpointPurposesAreNotInterchangeable() {
        String eligibility=ticket("eligibility",claims("eligibility")),playback=ticket("playback",claims("playback")),review=ticket("review",claims("review"));
        rejects(playback);rejects(review);assertThrows(ResponseStatusException.class,()->verifier.playback(eligibility,user,false));
        assertThrows(ResponseStatusException.class,()->verifier.playback(playback,user,true));assertThrows(ResponseStatusException.class,()->verifier.playback(review,user,false));
        var access=new MediaJwtVerifier(SimulationExamTestTokens.SECRET,"dts-identity-service");
        assertThrows(Exception.class,()->access.verify(playback));assertThrows(Exception.class,()->access.playbackClaims(playback));
        rejects(MediaTestTokens.ticket(MediaTestTokens.ticketClaims(user,media)));rejects(SimulationCatalogTestTokens.ticket(SimulationCatalogTestTokens.claims(user,media,session)));
    }
    @ParameterizedTest @ValueSource(strings={"JWT","dts-service+jwt","dts-simulation-catalog+jwt"})
    void explicitExamTokenTypeIsRequired(String type) {
        rejects(Jwts.builder().header().type(type).and().claims(claims("eligibility")).signWith(SimulationExamTestTokens.key("eligibility"),Jwts.SIG.HS256).compact());
    }
    @ParameterizedTest @ValueSource(strings={"","invalid","a.b.c","oversized"})
    void malformedInputIsRejected(String input){rejects(input.equals("oversized")?"x".repeat(16385):input);}
    @Test void nullTicketAndMissingConfigurationFailClosed(){rejects(null);assertThrows(IllegalStateException.class,()->new SimulationExamTicketVerifier(""));assertThrows(IllegalStateException.class,()->new SimulationExamTicketVerifier(null));}
}
