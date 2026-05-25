package io.conddo.studio.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.UUID;

/**
 * Service-to-service job intake from the platform (SERVICE_TOPOLOGY.md §4) — e.g.
 * an owner's website change-request becomes a {@code WEBSITE_REVISION} job.
 * {@code brief} carries the originating context (source record id, business name,
 * the request details).
 */
public record IntakeJobRequest(
        @NotBlank String jobTypeId,
        @NotNull UUID tenantId,
        @NotBlank String title,
        Map<String, Object> brief
) {
}
