package io.conddo.studio.portability;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bundle manifest spec (§22.2). The schema version is bumped if the bundle
 * layout ever changes — future readers can branch on it. {@code checksum} is
 * the SHA-256 over every file in the ZIP except manifest.json itself.
 */
public record BundleManifest(int schemaVersion,
                             OffsetDateTime exportedAt,
                             Actor exportedBy,
                             JobSummary job,
                             SiteSummary site,
                             List<AssetSummary> assets,
                             String checksum) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public record Actor(UUID staffId, String name, String email) {
    }

    public record JobSummary(UUID id, String jobNumber, int version, String title,
                             String jobType, String status, UUID tenantId,
                             Map<String, Object> brief) {
    }

    public record SiteSummary(int version, String status) {
    }

    public record AssetSummary(UUID id, String filename, long bytes, String sha256) {
    }
}
