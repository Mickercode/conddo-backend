package io.conddo.api.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body for {@code POST /api/v1/website/domain} (§11.2) — connect a custom
 * domain. A bare hostname, e.g. {@code shop.example.com} (no scheme or path).
 */
public record ConnectDomainBody(@NotBlank String domain) {
}
