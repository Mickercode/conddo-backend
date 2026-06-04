package io.conddo.payments.common;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

/**
 * Reads the tenant id from the JWT issued by conddo-api. The token's
 * {@code tenant_id} claim is the platform's tenant uuid — the same one
 * the FE sends Bearer headers with. Payments service trusts it because
 * both services share the issuer + RSA public key.
 */
public final class TenantPrincipal {

    private TenantPrincipal() {
    }

    public static UUID tenantId(Jwt jwt) {
        String value = jwt.getClaimAsString("tenant_id");
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("JWT has no tenant_id claim");
        }
        return UUID.fromString(value);
    }

    public static UUID userId(Jwt jwt) {
        String value = jwt.getSubject();
        return value == null ? null : UUID.fromString(value);
    }
}
