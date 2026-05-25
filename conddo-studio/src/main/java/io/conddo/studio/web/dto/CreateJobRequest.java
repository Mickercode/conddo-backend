package io.conddo.studio.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;
import java.util.UUID;

/**
 * Create a job (admin). Until auto-create-on-signup (TenantActivated event) is
 * wired, this is how jobs enter the board. {@code jobTypeId} e.g. WEBSITE_BUILD.
 */
public record CreateJobRequest(
        @NotBlank String jobTypeId,
        UUID tenantId,
        @NotBlank String title,
        Map<String, Object> brief
) {
}
