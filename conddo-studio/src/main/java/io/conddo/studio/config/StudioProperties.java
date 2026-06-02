package io.conddo.studio.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/** Studio service configuration, bound from {@code studio.*}. */
@ConfigurationProperties(prefix = "studio")
public record StudioProperties(Jwt jwt, Sla sla, Cors cors, Service service, Ai ai) {

    /** Internal staff JWT — HMAC (HS256). The secret must be ≥ 32 bytes. */
    public record Jwt(String secret, String issuer, Duration accessTtl, Duration refreshTtl) {
    }

    /** SLA alert thresholds (hours to deadline) for the GREEN/AMBER/RED tone. */
    public record Sla(int amberHours, int redHours) {
    }

    /**
     * Browser origins for the Studio frontend. Two complementary forms:
     * <ul>
     *   <li>{@code allowedOrigins} — exact list (STUDIO_CORS_ALLOWED_ORIGINS).</li>
     *   <li>{@code allowedOriginPatterns} — wildcards (STUDIO_CORS_ALLOWED_ORIGIN_PATTERNS),
     *       e.g. {@code https://*.vercel.app} for Vercel preview deploys.</li>
     * </ul>
     * Bare {@code "*"} is disallowed because the Studio passes credentials.
     */
    public record Cors(List<String> allowedOrigins, List<String> allowedOriginPatterns) {
    }

    /**
     * Service-to-service auth for the platform → Studio job hand-off
     * (SERVICE_TOPOLOGY.md §4). The shared secret the platform sends in
     * {@code X-Studio-Service-Token} on {@code /api/jobs/intake}. Blank/absent
     * disables intake (it then always 401s).
     */
    public record Service(String token) {
    }

    /**
     * AI assistant (§8) — Claude (Anthropic) for copy generation, QA scan, and
     * colour palettes. Bound from {@code studio.ai.claude.*}. A blank
     * {@code apiKey} keeps the assistant dormant (endpoints return
     * {@code available:false} — never an error, per the §20 AI rules).
     */
    public record Ai(Claude claude) {
        public record Claude(String apiKey, String model) {
        }
    }
}
