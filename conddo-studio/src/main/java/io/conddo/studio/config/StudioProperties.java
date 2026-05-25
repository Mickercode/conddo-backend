package io.conddo.studio.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/** Studio service configuration, bound from {@code studio.*}. */
@ConfigurationProperties(prefix = "studio")
public record StudioProperties(Jwt jwt, Sla sla, Cors cors, Service service) {

    /** Internal staff JWT — HMAC (HS256). The secret must be ≥ 32 bytes. */
    public record Jwt(String secret, String issuer, Duration accessTtl, Duration refreshTtl) {
    }

    /** SLA alert thresholds (hours to deadline) for the GREEN/AMBER/RED tone. */
    public record Sla(int amberHours, int redHours) {
    }

    public record Cors(List<String> allowedOrigins) {
    }

    /**
     * Service-to-service auth for the platform → Studio job hand-off
     * (SERVICE_TOPOLOGY.md §4). The shared secret the platform sends in
     * {@code X-Studio-Service-Token} on {@code /api/jobs/intake}. Blank/absent
     * disables intake (it then always 401s).
     */
    public record Service(String token) {
    }
}
