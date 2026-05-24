package io.conddo.core.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Hashes and verifies user passwords with BCrypt at cost 12 (PRD §12.1).
 *
 * <p>Wraps Spring Security's {@link PasswordEncoder} behind a small, intent-
 * revealing API so the cost factor lives in exactly one place.
 */
@Component
public class PasswordHasher {

    /** BCrypt work factor. 12 ≈ a few hundred ms per hash — tuned per PRD §12.1. */
    private static final int COST = 12;

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(COST);

    public String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    public boolean matches(String rawPassword, String passwordHash) {
        return encoder.matches(rawPassword, passwordHash);
    }
}
