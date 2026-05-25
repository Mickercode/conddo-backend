package io.conddo.studio.auth;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

/** Reads the authenticated staff id/role from the validated STUDIO_JWT. */
public final class StudioPrincipal {

    private StudioPrincipal() {
    }

    public static UUID staffId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    public static String role(Jwt jwt) {
        return jwt.getClaimAsString(StudioJwtService.CLAIM_ROLE);
    }
}
