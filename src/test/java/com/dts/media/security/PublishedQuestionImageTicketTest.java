package com.dts.media.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PublishedQuestionImageTicketTest {
    private final String secret = Base64.getEncoder().encodeToString(
            "published-question-image-test-secret-012345678901234567890123".getBytes(StandardCharsets.UTF_8));
    private final MediaJwtVerifier verifier = new MediaJwtVerifier(secret, "identity-test");
    private final UUID user = UUID.randomUUID();
    private final UUID media = UUID.randomUUID();

    String ticket(UUID actor, UUID asset, String purpose, String bank, Instant issued, Instant expiry) throws Exception {
        byte[] root = Base64.getDecoder().decode(secret);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(root, "HmacSHA256"));
        var key = Keys.hmacShaKeyFor(mac.doFinal("dts-media:published-question-image:v1".getBytes(StandardCharsets.UTF_8)));
        return Jwts.builder().issuer("dts-practice").subject(actor.toString())
                .audience().add("dts-media").and()
                .issuedAt(Date.from(issued)).expiration(Date.from(expiry))
                .claim("purpose", purpose).claim("mediaId", asset.toString())
                .claim("bankVersion", bank).signWith(key, Jwts.SIG.HS256).compact();
    }

    @Test void validPublishedQuestionTicketRequiresMatchingActorAndMedia() throws Exception {
        Instant now = Instant.now();
        String proof = ticket(user, media, "PUBLISHED_QUESTION_IMAGE", "dts-2026-600-v1", now.minusSeconds(2), now.plusSeconds(30));
        assertDoesNotThrow(() -> verifier.verifyPublishedQuestionImageTicket(proof, user, media));
        assertThrows(RuntimeException.class, () -> verifier.verifyPublishedQuestionImageTicket(proof, UUID.randomUUID(), media));
        assertThrows(RuntimeException.class, () -> verifier.verifyPublishedQuestionImageTicket(proof, user, UUID.randomUUID()));
    }

    @Test void draftWrongPurposeLongExpiryOrExpiredProofIsRejected() throws Exception {
        Instant now = Instant.now();
        assertThrows(RuntimeException.class, () -> verifier.verifyPublishedQuestionImageTicket(
                ticket(user, media, "DRAFT_IMAGE", "dts-2026-600-v1", now.minusSeconds(1), now.plusSeconds(20)), user, media));
        assertThrows(RuntimeException.class, () -> verifier.verifyPublishedQuestionImageTicket(
                ticket(user, media, "PUBLISHED_QUESTION_IMAGE", "draft-version", now.minusSeconds(1), now.plusSeconds(20)), user, media));
        assertThrows(RuntimeException.class, () -> verifier.verifyPublishedQuestionImageTicket(
                ticket(user, media, "PUBLISHED_QUESTION_IMAGE", "dts-2026-600-v1", now.minusSeconds(1), now.plusSeconds(100)), user, media));
        assertThrows(RuntimeException.class, () -> verifier.verifyPublishedQuestionImageTicket(
                ticket(user, media, "PUBLISHED_QUESTION_IMAGE", "dts-2026-600-v1", now.minusSeconds(90), now.minusSeconds(30)), user, media));
    }
}
