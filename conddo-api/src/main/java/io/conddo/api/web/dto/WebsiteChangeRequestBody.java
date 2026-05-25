package io.conddo.api.web.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body for {@code POST /api/v1/website/change-requests} (§11.2) — an owner's
 * edit request. {@code area} (which section) is optional; {@code details} is
 * required.
 */
public record WebsiteChangeRequestBody(String area, @NotBlank String details) {
}
