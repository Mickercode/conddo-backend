package io.conddo.api.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Browser origins permitted to call the API (CORS). Bound from
 * {@code conddo.security.cors.allowed-origins} (comma-separated env var). These
 * must be explicit origins — not {@code "*"} — because the API allows
 * credentials so the refresh cookie can be sent on {@code /auth/*}.
 */
@ConfigurationProperties(prefix = "conddo.security.cors")
public record CorsProperties(
        List<String> allowedOrigins
) {
}
