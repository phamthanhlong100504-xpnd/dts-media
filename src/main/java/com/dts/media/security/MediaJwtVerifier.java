package com.dts.media.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Component
public class MediaJwtVerifier {
    private final SecretKey accessKey;
    private final SecretKey playbackKey;
    private final SecretKey questionImageKey;
    private final String issuer;

    public MediaJwtVerifier(@Value("${jwt.access-token.secret:${JWT_ACCESS_SECRET:}}") String secret,
                            @Value("${jwt.issuer:dts-identity-service}") String issuer) {
        if (secret == null || secret.isBlank()) throw new IllegalStateException("Configure JWT_ACCESS_SECRET from Identity before starting Media");
        try {
            byte[] bytes;
            try { bytes = Decoders.BASE64.decode(secret); }
            catch (Exception e) {
                try { bytes = Decoders.BASE64URL.decode(secret); }
                catch (Exception ignored) { bytes = secret.getBytes(StandardCharsets.UTF_8); }
            }
            if (bytes.length < 32) bytes = MessageDigest.getInstance("SHA-256").digest(bytes);
            this.accessKey = Keys.hmacShaKeyFor(bytes);
            // Separate signing domain: playback tickets can never be access JWTs.
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(bytes, "HmacSHA256"));
            this.playbackKey = Keys.hmacShaKeyFor(mac.doFinal("dts-media:simulation-playback:v1".getBytes(StandardCharsets.UTF_8)));
            this.questionImageKey = Keys.hmacShaKeyFor(mac.doFinal("dts-media:published-question-image:v1".getBytes(StandardCharsets.UTF_8)));
            this.issuer = Objects.requireNonNull(issuer);
        } catch (Exception e) { throw new IllegalStateException("Invalid Media JWT configuration"); }
    }
    public MediaActor verify(String token) {
        Claims claims = Jwts.parser().verifyWith(accessKey).requireIssuer(issuer).build().parseSignedClaims(token).getPayload();
        if (claims.getExpiration() == null || claims.getIssuedAt() == null || claims.getIssuedAt().after(new Date())
                || (claims.get("type") != null && !"access".equals(claims.get("type"))))
            throw new IllegalArgumentException("Invalid access token claims");
        UUID user = canonicalUuid(claims.getSubject());
        Set<String> roles = strings(claims.get("roles"), "ROLE_[A-Za-z0-9_:-]+");
        Set<String> permissions = strings(claims.get("permissions"), "[a-z][a-z0-9_-]*:[a-z][a-z0-9_-]*");
        return new MediaActor(user, roles, permissions);
    }
    /** A valid caller JWT and this separate, short-lived proof are BOTH required. */
    public void verifyPublishedQuestionImageTicket(String ticket, UUID actorId, UUID mediaId) {
        try {
            Claims claims = Jwts.parser().verifyWith(questionImageKey).requireIssuer("dts-practice")
                    .require("purpose", "PUBLISHED_QUESTION_IMAGE").build().parseSignedClaims(ticket).getPayload();
            if (claims.getExpiration() == null || claims.getIssuedAt() == null
                    || claims.getIssuedAt().after(new Date()) || !claims.getExpiration().after(claims.getIssuedAt())
                    || claims.getExpiration().getTime() - claims.getIssuedAt().getTime() > 45_000
                    || !claims.getAudience().contains("dts-media")
                    || !actorId.equals(canonicalUuid(claims.getSubject()))
                    || !mediaId.equals(canonicalUuid(claims.get("mediaId", String.class)))
                    || !"dts-2026-600-v1".equals(claims.get("bankVersion", String.class)))
                throw new IllegalArgumentException("Invalid published-question image ticket");
        } catch (Exception ex) {
            // Do not reveal which proof field failed or echo signed tokens to logs.
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "Image not available for this account");
        }
    }

    public Claims playbackClaims(String ticket) {
        return Jwts.parser().verifyWith(playbackKey).requireIssuer("dts-practice")
                .require("purpose", "SIMULATION_LEARNING").build().parseSignedClaims(ticket).getPayload();
    }
    public static UUID canonicalUuid(String value) {
        UUID result = UUID.fromString(Objects.requireNonNull(value));
        if (!result.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException("Non-canonical UUID");
        return result;
    }
    private static Set<String> strings(Object value, String pattern) {
        if (value == null) return Set.of();
        if (!(value instanceof Collection<?> items) || items.size() > 500) throw new IllegalArgumentException("Invalid token authority claims");
        Set<String> result = new HashSet<>();
        for (Object item : items) {
            if (!(item instanceof String text) || text.length() > 255 || !text.matches(pattern))
                throw new IllegalArgumentException("Invalid token authority claims");
            result.add(text);
        }
        return Set.copyOf(result);
    }
}
