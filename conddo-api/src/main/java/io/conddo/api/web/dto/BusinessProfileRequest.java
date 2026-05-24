package io.conddo.api.web.dto;

import jakarta.validation.constraints.Email;

/** PUT business profile (§11.11). All optional; industry/subdomain are not editable. */
public record BusinessProfileRequest(
        String name,
        String tagline,
        String description,
        @Email String email,
        String phone
) {
}
