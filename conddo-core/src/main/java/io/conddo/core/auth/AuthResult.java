package io.conddo.core.auth;

import java.time.Duration;
import java.util.UUID;

/**
 * Outcome of a successful authentication: a signed access token plus the raw
 * refresh token (the only time the latter exists in plaintext — it is stored
 * hashed). The web layer puts the access token in the body and the refresh
 * token in an httpOnly cookie.
 */
public record AuthResult(
        String accessToken,
        Duration accessTokenTtl,
        String refreshToken,
        Duration refreshTokenTtl,
        UUID userId,
        String role
) {
}
