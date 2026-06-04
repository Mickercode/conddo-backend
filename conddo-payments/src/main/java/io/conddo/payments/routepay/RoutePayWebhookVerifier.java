package io.conddo.payments.routepay;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Verifies RoutePay webhook signatures. RoutePay sends
 * {@code X-RoutePay-Signature: sha256=<hex>}, computed as
 * {@code HMAC-SHA256(webhookSecret, rawBody)}. We compare in constant time.
 *
 * <p>If no {@code ROUTEPAY_WEBHOOK_SECRET} is configured, every request is
 * rejected — never accept unsigned webhooks, even in dev. Tests inject a known
 * secret via {@code @TestPropertySource}.
 */
@Component
public class RoutePayWebhookVerifier {

    private static final Logger log = LoggerFactory.getLogger(RoutePayWebhookVerifier.class);
    private static final String SCHEME_PREFIX = "sha256=";

    private final String secret;

    public RoutePayWebhookVerifier(@Value("${routepay.webhook-secret:}") String secret) {
        this.secret = secret == null ? "" : secret;
        if (this.secret.isBlank()) {
            log.warn("ROUTEPAY_WEBHOOK_SECRET is not set — every inbound webhook will be rejected");
        }
    }

    /** Returns {@code true} only when the signature parses and matches. */
    public boolean verify(String signatureHeader, byte[] rawBody) {
        if (secret.isBlank() || signatureHeader == null || signatureHeader.isBlank() || rawBody == null) {
            return false;
        }
        String header = signatureHeader.trim();
        if (header.startsWith(SCHEME_PREFIX)) {
            header = header.substring(SCHEME_PREFIX.length());
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String expected = HexFormat.of().formatHex(mac.doFinal(rawBody));
            return constantTimeEquals(expected, header);
        } catch (Exception ex) {
            log.debug("Webhook signature verify failed: {}", ex.getMessage());
            return false;
        }
    }

    /** SHA-256 of the raw body, used as the dedupe fallback when no event id is present. */
    public String sha256Hex(byte[] rawBody) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(rawBody));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
