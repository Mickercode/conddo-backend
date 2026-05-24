package io.conddo.api.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Internal-staff login (PRD v1.3 §22). Staff are global — no tenant slug. */
public record StaffLoginRequest(
        @NotBlank @Email String email,
        @NotBlank String password
) {
}
