package io.conddo.core.auth;

import java.time.Duration;
import java.util.UUID;

/**
 * Outcome of a successful staff authentication. Staff get an access token only
 * in Phase 1 (no refresh token yet — see StaffAuthService); SUPER_ADMIN is
 * occasional ops use, and staff refresh lands with Conddo Studio (Phase 3).
 */
public record StaffAuthResult(
        String accessToken,
        Duration accessTokenTtl,
        UUID staffId,
        String internalRole
) {
}
