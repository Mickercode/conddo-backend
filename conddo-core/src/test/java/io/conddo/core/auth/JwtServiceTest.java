package io.conddo.core.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the access-token contract end to end with a real RSA key pair:
 * the right claims go in, signature + expiry are enforced, and a token signed
 * by one key is rejected by another. No Spring context — pure unit test.
 */
class JwtServiceTest {

    private static final String ISSUER = "https://conddo.io";
    private static final Duration TTL = Duration.ofMinutes(15);

    private static KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    private static JwtService serviceWithClock(KeyPair kp, Clock clock) {
        return new JwtService(
                (RSAPublicKey) kp.getPublic(), (RSAPrivateKey) kp.getPrivate(), ISSUER, TTL, clock);
    }

    @Test
    void issuesTokenCarryingSubjectTenantAndRole() throws Exception {
        // Issue "now" (seconds precision — JWT timestamps are NumericDate) so the
        // token is still within its TTL when the decoder validates expiry against
        // the real clock, while keeping exact timestamp assertions.
        Instant fixed = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        JwtService service = serviceWithClock(rsaKeyPair(), Clock.fixed(fixed, ZoneOffset.UTC));
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        String token = service.issueAccessToken(userId, tenantId, "TENANT_ADMIN");
        Jwt decoded = service.decode(token);

        assertEquals(userId.toString(), decoded.getSubject());
        assertEquals(tenantId.toString(), decoded.getClaimAsString(JwtService.CLAIM_TENANT_ID));
        assertEquals("TENANT_ADMIN", decoded.getClaimAsString(JwtService.CLAIM_ROLE));
        assertEquals(ISSUER, decoded.getIssuer().toString());
        assertEquals(fixed, decoded.getIssuedAt());
        assertEquals(fixed.plus(TTL), decoded.getExpiresAt());
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        // Issue at a clock far in the past so the 15-min TTL is already elapsed.
        Instant longAgo = Instant.now().minus(Duration.ofDays(1));
        JwtService service = serviceWithClock(rsaKeyPair(), Clock.fixed(longAgo, ZoneOffset.UTC));

        String token = service.issueAccessToken(UUID.randomUUID(), UUID.randomUUID(), "STAFF");

        JwtException ex = assertThrows(JwtException.class, () -> service.decode(token));
        assertTrue(ex.getMessage().toLowerCase().contains("expired"),
                "Expected an expiry validation failure, got: " + ex.getMessage());
    }

    @Test
    void rejectsTokenSignedByADifferentKey() throws Exception {
        JwtService issuer = serviceWithClock(rsaKeyPair(), Clock.systemUTC());
        JwtService otherKeyHolder = serviceWithClock(rsaKeyPair(), Clock.systemUTC());

        String token = issuer.issueAccessToken(UUID.randomUUID(), UUID.randomUUID(), "CUSTOMER");

        // A verifier holding a different public key must reject the signature.
        assertThrows(JwtException.class, () -> otherKeyHolder.decode(token));
    }
}
