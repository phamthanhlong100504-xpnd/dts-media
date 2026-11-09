package com.dts.media.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class SimulationCatalogTestTokens {
    public static final String SECRET = MediaTestTokens.SECRET;
    public static final SecretKey CATALOG;
    static {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(SECRET), "HmacSHA256"));
            CATALOG = Keys.hmacShaKeyFor(mac.doFinal("dts-media:simulation-catalog-thumbnail:v1".getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private SimulationCatalogTestTokens() {}
    public static Map<String,Object> claims(UUID user, UUID media, UUID scenario) {
        Map<String,Object> claims = new HashMap<>();
        claims.put("iss", "dts-practice"); claims.put("aud", List.of("dts-media")); claims.put("sub", user.toString());
        claims.put("iat", new Date(System.currentTimeMillis()-1000)); claims.put("exp", new Date(System.currentTimeMillis()+44000));
        claims.put("jti", UUID.randomUUID().toString()); claims.put("purpose", "SIMULATION_CATALOG_THUMBNAIL");
        claims.put("mediaId", media.toString()); claims.put("scenarioId", scenario.toString());
        claims.put("bankVersion", "dts-simulation-120-card01-v1"); claims.put("targetType", "SIMULATION_THUMBNAIL");
        return claims;
    }
    public static String ticket(Map<String,Object> claims) {
        return Jwts.builder().header().type("dts-simulation-catalog+jwt").and().claims(claims).signWith(CATALOG, Jwts.SIG.HS256).compact();
    }
    public static String access(UUID user, String... rights) {
        return MediaTestTokens.access(user, List.of("ROLE_STUDENT"), rights);
    }
}
