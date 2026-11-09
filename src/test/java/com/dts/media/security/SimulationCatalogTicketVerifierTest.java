package com.dts.media.security;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SimulationCatalogTicketVerifierTest {
    final SimulationCatalogTicketVerifier verifier = new SimulationCatalogTicketVerifier(SimulationCatalogTestTokens.SECRET);
    final UUID user = UUID.randomUUID(), media = UUID.randomUUID(), scenario = UUID.randomUUID();
    Map<String,Object> claims() { return SimulationCatalogTestTokens.claims(user,media,scenario); }
    void rejects(String token) {
        var e = assertThrows(ResponseStatusException.class, () -> verifier.verify(token,user));
        assertEquals(403,e.getStatusCode().value());
        if (token != null && !token.isEmpty()) assertFalse(e.getReason().contains(token));
    }
    @Test void validatesCatalogueThumbnailWithoutAttemptAndRetainsItsBinding() {
        var result = verifier.verify(SimulationCatalogTestTokens.ticket(claims()),user);
        assertEquals(media,result.mediaId()); assertEquals(scenario,result.scenarioId());
        assertEquals("dts-simulation-120-card01-v1",result.bankVersion());
    }
    @ParameterizedTest @ValueSource(strings={"iss","aud","sub","iat","exp","jti","purpose","mediaId","scenarioId","bankVersion","targetType"})
    void mandatoryClaimCannotBeOmitted(String name) {
        var c=claims();c.remove(name);rejects(SimulationCatalogTestTokens.ticket(c));
    }
    @ParameterizedTest @ValueSource(strings={"issuer","audience","multi-audience","other-user","future","expired","long-life","zero-life","jti","media","scenario","version","video","purpose","attempt","claim-type"})
    void invalidOrBroaderScopeIsRejected(String kind) {
        var c=claims();
        switch(kind) {
            case "issuer" -> c.put("iss","other-service");
            case "audience" -> c.put("aud",List.of("other-service"));
            case "multi-audience" -> c.put("aud",List.of("dts-media","other-service"));
            case "other-user" -> c.put("sub",UUID.randomUUID().toString());
            case "future" -> { c.put("iat",new Date(System.currentTimeMillis()+10000));c.put("exp",new Date(System.currentTimeMillis()+20000)); }
            case "expired" -> { c.put("iat",new Date(1000));c.put("exp",new Date(2000)); }
            case "long-life" -> c.put("exp",new Date(System.currentTimeMillis()+120000));
            case "zero-life" -> { var date=new Date(System.currentTimeMillis()+10000);c.put("iat",date);c.put("exp",date); }
            case "jti" -> c.put("jti","not-a-uuid");
            case "media" -> c.put("mediaId","1-1-1-1-1");
            case "scenario" -> c.put("scenarioId","1-1-1-1-1");
            case "version" -> c.put("bankVersion","../current");
            case "video" -> c.put("targetType","SIMULATION_VIDEO");
            case "purpose" -> c.put("purpose","SIMULATION_LEARNING");
            case "attempt" -> c.put("attemptId",UUID.randomUUID().toString());
            case "claim-type" -> c.put("mediaId",42);
        }
        rejects(SimulationCatalogTestTokens.ticket(c));
    }
    @ParameterizedTest @ValueSource(strings={"JWT","dts-service+jwt","dts-simulation-playback+jwt"})
    void explicitTokenTypeIsRequired(String type) {
        rejects(Jwts.builder().header().type(type).and().claims(claims()).signWith(SimulationCatalogTestTokens.CATALOG,Jwts.SIG.HS256).compact());
    }
    @Test void signingDomainsAreNotInterchangeable() {
        rejects(MediaTestTokens.ticket(MediaTestTokens.ticketClaims(user,media)));
        rejects(SimulationCatalogTestTokens.access(user,"media:read","simulations:read"));
        String catalog=SimulationCatalogTestTokens.ticket(claims());
        var access = new MediaJwtVerifier(SimulationCatalogTestTokens.SECRET,"dts-identity-service");
        assertThrows(Exception.class,()->access.verify(catalog));
        assertThrows(Exception.class,()->access.playbackClaims(catalog));
        rejects(Jwts.builder().header().type("dts-simulation-catalog+jwt").and().claims(claims()).signWith(MediaTestTokens.PLAYBACK).compact());
    }
    @ParameterizedTest @ValueSource(strings={"","invalid","a.b.c","e30.e30.","oversized"})
    void malformedInputIsDenied(String value) { rejects("oversized".equals(value)?"x".repeat(8193):value); }
    @Test void missingConfigurationFailsClosed() { assertThrows(IllegalStateException.class,()->new SimulationCatalogTicketVerifier("")); }
}
