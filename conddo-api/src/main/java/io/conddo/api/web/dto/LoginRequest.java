package io.conddo.api.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Login credentials. The tenant slug scopes the lookup — {@code email} is unique
 * only within a tenant. (When subdomain resolution lands in Phase 1 item 2, the
 * slug will be derived from the host instead of sent in the body.)
 */
public record LoginRequest(
        @NotBlank String tenantSlug,
        @NotBlank @Email String email,
        @NotBlank String password
) {
}
