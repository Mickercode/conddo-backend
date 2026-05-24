package io.conddo.core.auth;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Generates the random parts of opaque "selector.verifier" credentials
 * (refresh tokens, password-reset tokens). The selector is a public lookup key;
 * the verifier is the secret that is BCrypt-hashed at rest. Both are
 * high-entropy and URL-safe so they survive cookies and links unescaped.
 */
public final class OpaqueToken {

    /** Selector entropy: 128 bits — only needs to be unique, not secret. */
    public static final int SELECTOR_BYTES = 16;
    /** Verifier entropy: 256 bits — the actual secret. */
    public static final int VERIFIER_BYTES = 32;
    /** Separates selector from verifier in the token presented to the client. */
    public static final char SEPARATOR = '.';

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private OpaqueToken() {
    }

    public static String randomBase64Url(int numBytes) {
        byte[] buffer = new byte[numBytes];
        RANDOM.nextBytes(buffer);
        return ENCODER.encodeToString(buffer);
    }
}
