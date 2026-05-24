package io.conddo.core.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Confirms real BCrypt behaviour: the hash is salted (not the plaintext, and
 * distinct per call) and verifies correctly. Kept tiny — two hash operations —
 * because BCrypt at cost 12 is intentionally slow.
 */
class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void hashesAndVerifies() {
        String raw = "s3cret-passw0rd";
        String hash = hasher.hash(raw);

        assertNotEquals(raw, hash, "must not store plaintext");
        assertTrue(hash.startsWith("$2"), "expected a BCrypt hash");
        assertTrue(hasher.matches(raw, hash));
        assertFalse(hasher.matches("wrong", hash));
    }
}
