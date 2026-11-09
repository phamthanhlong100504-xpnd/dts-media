package com.dts.media.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class MediaTestTokens {
    static final String SECRET = "bWVkaWEtc2VjdXJpdHktZml4dHVyZS1vbmx5LTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDEyMzQ1Njc4OQ==";
    static final SecretKey ACCESS = Keys.hmacShaKeyFor(Decoders.BASE64.decode(SECRET));
    static final SecretKey PLAYBACK;
    static {
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(Decoders.BASE64.decode(SECRET), "HmacSHA256"));
            PLAYBACK = Keys.hmacShaKeyFor(mac.doFinal("dts-media:simulation-playback:v1".getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    static String access(UUID user, List<String> roles, String... permissions) {
        return Jwts.builder().issuer("dts-identity-service").subject(user.toString()).issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis()+300000)).claim("roles",roles).claim("permissions",List.of(permissions))
                .signWith(ACCESS).compact();
    }
    static Map<String,Object> ticketClaims(UUID user, UUID media) {
        Map<String,Object> claims = new HashMap<>();
        claims.put("iss","dts-practice"); claims.put("aud",List.of("dts-media")); claims.put("sub",user.toString());
        claims.put("iat",new Date()); claims.put("exp",new Date(System.currentTimeMillis()+90000));
        claims.put("purpose","SIMULATION_LEARNING"); claims.put("attemptId",UUID.randomUUID().toString());
        claims.put("mediaId",media.toString()); claims.put("bankVersion","simulation-v1"); claims.put("targetType","SIMULATION_VIDEO");
        return claims;
    }
    static String ticket(Map<String,Object> claims) { return Jwts.builder().claims(claims).signWith(PLAYBACK).compact(); }
}
