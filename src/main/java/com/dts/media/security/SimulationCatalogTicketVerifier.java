package com.dts.media.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Practice attests a published-bank thumbnail without creating a learning attempt. */
@Component
public class SimulationCatalogTicketVerifier {
    private final SecretKey key;

    public SimulationCatalogTicketVerifier(@Value("${jwt.access-token.secret:${JWT_ACCESS_SECRET:}}") String secret) {
        if (secret == null || secret.isBlank()) throw new IllegalStateException("Configure JWT_ACCESS_SECRET from Identity before starting Media");
        try {
            byte[] bytes;
            try { bytes = Decoders.BASE64.decode(secret); }
            catch (Exception e) {
                try { bytes = Decoders.BASE64URL.decode(secret); }
                catch (Exception ignored) { bytes = secret.getBytes(StandardCharsets.UTF_8); }
            }
            if (bytes.length < 32) bytes = MessageDigest.getInstance("SHA-256").digest(bytes);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(bytes, "HmacSHA256"));
            key = Keys.hmacShaKeyFor(mac.doFinal("dts-media:simulation-catalog-thumbnail:v1".getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("Invalid Media catalogue ticket configuration"); }
    }

    public Scope verify(String ticket, UUID userId) {
        try {
            if (ticket == null || ticket.isBlank() || ticket.length() > 8192) throw new IllegalArgumentException();
            var signed = Jwts.parser().verifyWith(key).requireIssuer("dts-practice")
                    .require("purpose", "SIMULATION_CATALOG_THUMBNAIL").build().parseSignedClaims(ticket);
            Claims c = signed.getPayload();
            Instant now = Instant.now();
            if (!"HS256".equals(signed.getHeader().getAlgorithm())
                    || !"dts-simulation-catalog+jwt".equals(signed.getHeader().getType())
                    || c.getIssuedAt() == null || c.getExpiration() == null
                    || !Set.of("dts-media").equals(c.getAudience())
                    || c.getIssuedAt().toInstant().isAfter(now) || !c.getExpiration().toInstant().isAfter(now)
                    || !c.getExpiration().after(c.getIssuedAt())
                    || c.getExpiration().getTime() - c.getIssuedAt().getTime() > 60000
                    || !userId.equals(MediaJwtVerifier.canonicalUuid(c.getSubject()))
                    || !"SIMULATION_THUMBNAIL".equals(c.get("targetType", String.class))
                    || c.containsKey("attemptId")) throw new IllegalArgumentException();
            MediaJwtVerifier.canonicalUuid(c.getId());
            UUID scenario = MediaJwtVerifier.canonicalUuid(c.get("scenarioId", String.class));
            UUID media = MediaJwtVerifier.canonicalUuid(c.get("mediaId", String.class));
            String version = c.get("bankVersion", String.class);
            if (version == null || !version.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")) throw new IllegalArgumentException();
            return new Scope(media, scenario, version);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid or expired simulation catalogue ticket");
        }
    }

    public record Scope(UUID mediaId, UUID scenarioId, String bankVersion) {}
}
