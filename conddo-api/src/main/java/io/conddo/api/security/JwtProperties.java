package io.conddo.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

import java.time.Duration;

/**
 * RSA key material and timing for access-token signing (PRD §6.2 / §12.1).
 *
 * <p>Keys are PEM resources resolved from {@code conddo.security.jwt.*}. Locally
 * they default to a generated dev key pair on the classpath; in production the
 * {@code CONDDO_JWT_*} env vars point at mounted key files. The private key is
 * never committed (see {@code .gitignore}).
 */
@ConfigurationProperties(prefix = "conddo.security.jwt")
public record JwtProperties(
        Resource publicKey,
        Resource privateKey,
        String issuer,
        Duration accessTtl
) {
}
