package io.conddo.api.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Tenant signup. Beyond the business details, the caller supplies the tenant's
 * first administrator (TENANT_ADMIN); the account is created atomically with the
 * tenant so the business can log in immediately (PRD §13.1).
 */
public record CreateTenantRequest(
        @NotBlank String name,
        @NotBlank
        @Pattern(regexp = "^[a-z0-9](?:[a-z0-9-]{1,48}[a-z0-9])$",
                message = "slug must be lowercase letters, digits and hyphens")
        String slug,
        String verticalId,
        String planId,
        @NotBlank @Email String adminEmail,
        @NotBlank @Size(min = 8, message = "admin password must be at least 8 characters")
        String adminPassword,
        String adminFullName
) {
}
