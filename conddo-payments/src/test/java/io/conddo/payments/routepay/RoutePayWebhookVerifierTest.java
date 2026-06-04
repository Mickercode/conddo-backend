package io.conddo.payments.routepay;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HMAC verification: round-trip a known body with a known secret, reject
 * tampered bodies, mismatched secrets, missing headers, and the no-secret
 * configuration (which must never accept anything, even in dev).
 */
class RoutePayWebhookVerifierTest {

    private static final String SECRET = "hot-pepper-not-a-real-secret";
    private static final byte[] BODY = "{\"reference\":\"RP-amaka-x\",\"status\":\"PAID\",\"fee\":150}"
            .getBytes(StandardCharsets.UTF_8);

    private final RoutePayWebhookVerifier verifier = new RoutePayWebhookVerifier(SECRET);

    @Test
    void acceptsValidSignatureWithBareHex() {
        assertTrue(verifier.verify(hmac(SECRET, BODY), BODY));
    }

    @Test
    void acceptsValidSignatureWithSha256Prefix() {
        assertTrue(verifier.verify("sha256=" + hmac(SECRET, BODY), BODY));
    }

    @Test
    void rejectsTamperedBody() {
        byte[] tampered = "{\"reference\":\"RP-amaka-x\",\"status\":\"PAID\",\"fee\":99999}"
                .getBytes(StandardCharsets.UTF_8);
        assertFalse(verifier.verify(hmac(SECRET, BODY), tampered));
    }

    @Test
    void rejectsWrongSecret() {
        assertFalse(verifier.verify(hmac("different-secret", BODY), BODY));
    }

    @Test
    void rejectsMissingSignatureHeader() {
        assertFalse(verifier.verify(null, BODY));
        assertFalse(verifier.verify("", BODY));
        assertFalse(verifier.verify("   ", BODY));
    }

    @Test
    void unconfiguredVerifierRejectsEverything() {
        RoutePayWebhookVerifier off = new RoutePayWebhookVerifier("");
        assertFalse(off.verify(hmac(SECRET, BODY), BODY),
                "an unconfigured verifier must never accept any signature, even a correct one for some other secret");
    }

    @Test
    void sha256IsDeterministicForTheSameBody() {
        String a = verifier.sha256Hex(BODY);
        String b = verifier.sha256Hex(BODY);
        assertEquals(a, b, "sha256 must be deterministic — payload hash powers dedupe");
    }

    @Test
    void sha256DiffersForDifferentBodies() {
        String a = verifier.sha256Hex(BODY);
        String b = verifier.sha256Hex(("{\"x\":1}").getBytes(StandardCharsets.UTF_8));
        assertFalse(a.equals(b));
    }

    private static String hmac(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
